package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.samsung.gsre.agent.config.AgentProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 통신 게이트웨이. Orchestrator 는 "무엇을 보낼지" 만 알고, "어떻게 보내는지" 는 여기 있다.
 *
 * <p>프로토콜: MCP Streamable HTTP 규격의 {@code POST tools/call} (JSON-RPC 2.0).
 * 응답은 JSON 또는 SSE({@code data: {...}}) 둘 다 받아 결과 본문만 꺼낸다.
 * 결과는 {@code result.content[].text} 까지 벗겨서 Reshaping 입력으로 넘긴다.</p>
 *
 * <p>장애 대응: AUTO 모드에서 <b>연결 자체가 실패</b>하면 결정적 픽스처로 대체한다(데모/로컬).
 * HTTP 4xx/5xx 는 숨기지 않는다. 실제 서버가 응답한 오류는 그대로 감사 로그에 남긴다.</p>
 *
 * <p>보안: 인증 값은 DB 에 두지 않는다. {@code auth_config} 가 가리키는 환경변수에서 읽고,
 * 로그·감사 payload 에는 절대 쓰지 않는다.</p>
 */
@Slf4j
@Component
public class McpClientGateway {

    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final McpFixtureCatalog fixtures;
    private final HttpClient http;
    private final AtomicLong jsonRpcId = new AtomicLong(1);

    public McpClientGateway(ObjectMapper mapper, AgentProperties props, McpFixtureCatalog fixtures) {
        this.mapper = mapper;
        this.props = props;
        this.fixtures = fixtures;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, props.getMcp().getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public McpCallResult call(McpApiMaster api, JsonNode requestPayload) {
        String requestBody = buildRequestBody(api, requestPayload);
        McpTransportMode mode = McpTransportMode.from(props.getMcp().getTransportMode());

        if (mode == McpTransportMode.OFFLINE) {
            return fixture(api, requestBody, "transport-mode=OFFLINE");
        }

        long startedAt = System.nanoTime();
        try {
            HttpRequest request = buildRequest(api, requestBody);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = elapsedMs(startedAt);
            String payloadBody = extractPayloadBody(response.body());
            log.info("[mcp] call apiId={} tool={} status={} {}ms fallback=false",
                    api.apiId(), api.toolName(), response.statusCode(), elapsed);
            return new McpCallResult(response.body(), unwrap(payloadBody), response.statusCode(), elapsed,
                    requestBody, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("MCP 호출이 중단되었습니다: " + api.apiId(), e);
        } catch (Exception e) {
            if (mode == McpTransportMode.AUTO && props.getMcp().isOfflineFallbackEnabled()) {
                log.warn("[mcp] 연결 실패 → 오프라인 픽스처 사용 apiId={} cause={}", api.apiId(), e.toString());
                return fixture(api, requestBody,
                        "OFFLINE_FALLBACK " + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            throw new McpTransportException("MCP 엔드포인트 호출에 실패했습니다 (" + api.apiId() + " → "
                    + api.endpointUrl() + "): " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 요청 구성
    // ------------------------------------------------------------------

    private String buildRequestBody(McpApiMaster api, JsonNode payload) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "svcm-" + jsonRpcId.getAndIncrement());
        body.put("method", "tools/call");

        ObjectNode params = mapper.createObjectNode();
        if (payload != null && payload.isObject() && payload.has("tool") && payload.has("arguments")) {
            // INBOUND: 룰이 이미 MCP 스펙(tool/arguments)으로 감싼 payload
            params.put("name", payload.path("tool").asText(api.toolName()));
            params.set("arguments", payload.path("arguments"));
        } else {
            params.put("name", api.toolName());
            params.set("arguments", payload == null ? mapper.createObjectNode() : payload);
        }
        body.set("params", params);
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new McpTransportException("MCP 요청 본문을 만들지 못했습니다: " + api.apiId(), e);
        }
    }

    private HttpRequest buildRequest(McpApiMaster api, String requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(api.endpointUrl()))
                .timeout(Duration.ofMillis(api.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        applyAuth(api, builder);
        return builder.build();
    }

    /** auth_config 는 시크릿이 아니라 환경변수 참조다. 해석 규칙은 McpAuthHeaders 에 한 벌만 둔다. */
    private void applyAuth(McpApiMaster api, HttpRequest.Builder builder) {
        McpAuthHeaders.resolve(api, mapper).forEach(builder::header);
    }

    // ------------------------------------------------------------------
    // 응답 해석
    // ------------------------------------------------------------------

    /** SSE 로 온 응답에서 첫 data 프레임만 꺼낸다. 일반 JSON 이면 그대로 돌려준다. */
    private String extractPayloadBody(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
            for (String line : trimmed.split("\n")) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!data.isEmpty()) {
                        return data;
                    }
                }
            }
        }
        return body;
    }

    /** JSON-RPC 봉투 → result.content[].text → 실제 도구 결과 순으로 벗긴다. */
    private JsonNode unwrap(String body) {
        if (body == null || body.isBlank()) {
            return mapper.nullNode();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode content = root.path("result").path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                        return readTextOrKeep(block.get("text").asText());
                    }
                }
            }
            if (root.has("error")) {
                return root.get("error");
            }
            if (root.has("result")) {
                return root.get("result");
            }
            return root;
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(body);
        }
    }

    private JsonNode readTextOrKeep(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return mapper.readTree(trimmed);
            } catch (Exception ignored) {
                // JSON 이 아닌 텍스트는 그대로 둔다.
            }
        }
        return mapper.getNodeFactory().textNode(text);
    }

    private McpCallResult fixture(McpApiMaster api, String requestBody, String reason) {
        long startedAt = System.nanoTime();
        String raw = fixtures.rawResponse(api);
        long elapsed = elapsedMs(startedAt);
        return new McpCallResult(raw, unwrap(extractPayloadBody(raw)), 0, elapsed, requestBody, true, reason);
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
