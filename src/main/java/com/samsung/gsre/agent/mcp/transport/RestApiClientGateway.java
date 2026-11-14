package com.samsung.gsre.agent.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.common.constant.McpConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.exception.McpTransportException;
import com.samsung.gsre.agent.mcp.fixture.McpFixtureCatalog;
import com.samsung.gsre.agent.security.mask.SensitiveDataMasker;
import com.samsung.gsre.agent.security.net.OutboundTargetPolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REST API 통신 게이트웨이. MCP 와 형제 클래스이고, 역할 경계도 같다 —
 * Orchestrator 는 "무엇을 보낼지" 만 알고, "어떤 메서드로 어떤 URL 에 어떻게 보내는지" 는 여기 있다.
 *
 * <p>MCP({@link McpClientGateway}) 와 다른 점은 하나뿐이다. <b>JSON-RPC 봉투가 없다.</b>
 * 테이블의 {@code http_method} 대로 POST/PUT/PATCH 는 JSON 본문을 보내고,
 * GET/DELETE 는 본문 없이 남은 파라미터를 Query String 으로 붙인다.
 * {@code endpoint_url} 의 {@code {appId}} 같은 경로변수는 파라미터 값(URL 인코딩)으로 치환한다.</p>
 *
 * <p>프로토콜 선택도 코드가 아니라 데이터다. Orchestrator 는 {@code transport_type} 이
 * {@code REST}/{@code REST_API} 일 때만 이 클래스를 부른다. 새 REST API 는 행 1개 추가로 끝난다.</p>
 *
 * <p>장애 대응은 MCP 와 동일하다. AUTO 모드에서 <b>연결 자체가 실패</b>하면 결정적 픽스처로 대체하고,
 * HTTP 4xx/5xx 는 숨기지 않는다(서버가 응답한 오류를 가짜 성공으로 덮지 않는다).</p>
 */
@Slf4j
@Component
public class RestApiClientGateway {

    // ==================================================================
    // 상단 (INPUT) — 기준정보(패턴)와 의존성
    // ==================================================================

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final McpFixtureCatalog fixtures;
    private final OutboundTargetPolicy targetPolicy;
    private final HttpClient http;

    public RestApiClientGateway(ObjectMapper mapper, AgentProperties props, McpFixtureCatalog fixtures,
                                OutboundTargetPolicy targetPolicy) {
        this.mapper = mapper;
        this.props = props;
        this.fixtures = fixtures;
        this.targetPolicy = targetPolicy;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, props.getMcp().getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param payload Reshaping 을 거친 요청 payload. 본문 메서드면 그대로 body 가 되고,
     *                GET/DELETE 면 Query Parameter 후보가 된다.
     * @param params  호출 파라미터({pathVariable} 치환의 1순위).
     */
    // ==================================================================
    // 중단 (PROCESS) — 전송 흐름
    // ==================================================================

    public McpCallResult call(McpApiMaster api, JsonNode payload, Map<String, Object> params) {
        McpHttpMethod method = api.httpMethodValue();
        Map<String, Object> effective = effectiveParams(params, payload);
        String body = method.hasBody() ? writeJson(payload) : null;
        McpTransportMode mode = McpTransportMode.from(props.getMcp().getTransportMode());

        if (mode == McpTransportMode.OFFLINE) {
            return fixture(api, body, "transport-mode=OFFLINE");
        }

        // SSRF 방어: 등록 후 테이블이 바뀌었을 수 있으므로 전송 직전에 다시 검사한다.
        targetPolicy.verify(api.endpointUrl());

        long startedAt = System.nanoTime();
        try {
            HttpRequest request = buildRequest(api, method, effective, body);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = elapsedMs(startedAt);
            log.info("[rest] call apiId={} {} {} status={} {}ms fallback=false",
                    api.apiId(), method, SensitiveDataMasker.maskText(request.uri().toString()),
                    response.statusCode(), elapsed);
            return new McpCallResult(response.body(), parse(response.body()), response.statusCode(), elapsed,
                    body, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("REST 호출이 중단되었습니다: " + api.apiId(), e);
        } catch (Exception e) {
            if (mode == McpTransportMode.AUTO && props.getMcp().isOfflineFallbackEnabled()) {
                log.warn("[rest] 연결 실패 → 오프라인 픽스처 사용 apiId={} cause={}", api.apiId(), e.toString());
                return fixture(api, body,
                        "OFFLINE_FALLBACK " + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            throw new McpTransportException("REST 엔드포인트 호출에 실패했습니다 (" + api.apiId() + " → "
                    + api.endpointUrl() + "): " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 요청 구성
    // ------------------------------------------------------------------

    private HttpRequest buildRequest(McpApiMaster api, McpHttpMethod method, Map<String, Object> effective,
                                     String body) {
        URI uri = URI.create(buildUrl(api, method, effective));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(api.timeoutMs()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(McpConstants.HEADER_ACCEPT, McpConstants.CONTENT_TYPE_JSON);
        if (method.hasBody()) {
            headers.put(McpConstants.HEADER_CONTENT_TYPE, McpConstants.CONTENT_TYPE_JSON);
        }
        headers.putAll(McpAuthHeaders.customHeaders(api, mapper));
        headers.putAll(McpAuthHeaders.resolve(api, mapper));
        headers.forEach(builder::header);

        return builder.method(method.name(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    /** {pathVariable} 치환 + (GET/DELETE) 남은 파라미터 → Query String. */
    private String buildUrl(McpApiMaster api, McpHttpMethod method, Map<String, Object> effective) {
        String template = api.endpointUrl();
        java.util.Set<String> consumed = new java.util.LinkedHashSet<>();

        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder url = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = effective.get(name);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new McpTransportException("경로 변수 값을 찾지 못했습니다: {" + name + "} (apiId="
                        + api.apiId() + ") — params." + name + " 을 지정하세요.");
            }
            consumed.add(name);
            matcher.appendReplacement(url, Matcher.quoteReplacement(encodePath(String.valueOf(value))));
        }
        matcher.appendTail(url);

        if (method.hasBody()) {
            return url.toString();
        }
        String query = queryString(effective, consumed);
        if (query.isEmpty()) {
            return url.toString();
        }
        return url + (url.indexOf("?") >= 0 ? "&" : "?") + query;
    }

    private String queryString(Map<String, Object> effective, java.util.Set<String> consumed) {
        StringBuilder query = new StringBuilder();
        effective.forEach((key, value) -> {
            if (!consumed.contains(key) && value != null) {
                if (!query.isEmpty()) {
                    query.append('&');
                }
                query.append(encodeQuery(key)).append('=').append(encodeQuery(stringify(value)));
            }
        });
        return query.toString();
    }

    /** 경로 세그먼트 인코딩. URLEncoder 는 공백을 '+' 로 바꾸므로 %20 으로 되돌린다. */
    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 파라미터 병합: 호출 파라미터가 기본이고, Reshaping 을 거친 payload 객체 필드가 그 위에 덮인다.
     * (룰이 보낼 값을 정하고, 경로변수처럼 룰에 없는 값은 호출 파라미터에서 온다.)
     */
    private Map<String, Object> effectiveParams(Map<String, Object> params, JsonNode payload) {
        Map<String, Object> effective = new LinkedHashMap<>();
        if (params != null) {
            effective.putAll(params);
        }
        if (payload != null && payload.isObject()) {
            payload.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !value.isNull()) {
                    effective.put(entry.getKey(), value.isValueNode() ? value.asText() : value);
                }
            });
        }
        return effective;
    }

    private String stringify(Object value) {
        if (value instanceof JsonNode node) {
            if (node.isArray()) {
                StringBuilder joined = new StringBuilder();
                node.forEach(item -> {
                    if (!joined.isEmpty()) {
                        joined.append(',');
                    }
                    joined.append(item.asText());
                });
                return joined.toString();
            }
            return node.asText();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder joined = new StringBuilder();
            iterable.forEach(item -> {
                if (!joined.isEmpty()) {
                    joined.append(',');
                }
                joined.append(item);
            });
            return joined.toString();
        }
        return String.valueOf(value);
    }

    private String writeJson(JsonNode node) {
        if (node == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new McpTransportException("REST 요청 본문을 만들지 못했습니다", e);
        }
    }

    // ==================================================================
    // 하단 (OUTPUT) — 응답 해석 / 폴백 / 결과
    // ==================================================================

    /** REST 응답은 봉투가 없다. JSON 이면 그대로, 아니면 텍스트 노드로 넘긴다. */
    private JsonNode parse(String body) {
        if (ValidationUtils.isBlank(body)) {
            return mapper.nullNode();
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return mapper.readTree(trimmed);
            } catch (Exception ignored) {
                // JSON 이 아닌 텍스트는 아래에서 그대로 둔다.
            }
        }
        return mapper.getNodeFactory().textNode(body);
    }

    private McpCallResult fixture(McpApiMaster api, String body, String reason) {
        long startedAt = System.nanoTime();
        String raw = fixtures.rawResponse(api);
        long elapsed = elapsedMs(startedAt);
        return new McpCallResult(raw, parse(raw), 0, elapsed, body, true, reason);
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
