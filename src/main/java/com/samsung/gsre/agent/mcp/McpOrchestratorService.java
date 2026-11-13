package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.config.AgentProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP 연동 오케스트레이터. 이 클래스에는 <b>흐름만</b> 있다.
 *
 * <pre>
 * [1] 기준정보 로드   svcm_tb_mcp_api_master 에서 방향·룰·엔드포인트를 읽는다
 * [2] 요청 Reshaping  INBOUND: DB 로우 → MCP 스펙 / OUTBOUND: 호출 파라미터 정리
 * [3] 통신            McpClientGateway (tools/call, 오프라인 폴백 포함)
 * [4] 응답 Reshaping  Raw 응답 → 필요한 필드만 남긴 압축 JSON
 * [5] 감사로그        raw/reshaped 를 한 행에 나란히 남긴다 (실패 포함)
 * </pre>
 *
 * <p>JSONPath 해석은 {@link DataReshaper}, HTTP 는 {@link McpClientGateway}, 원천 SELECT 는
 * {@link McpSourceQueryReader} 가 맡는다. 새 도구를 추가할 때 이 파일은 고치지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpOrchestratorService {

    private final McpMasterRepository masterRepository;
    private final McpPayloadLogRepository logRepository;
    private final McpSourceQueryReader sourceReader;
    private final McpClientGateway gateway;
    private final DataReshaper reshaper;
    private final ObjectMapper objectMapper;
    private final AgentProperties props;

    // ------------------------------------------------------------------
    // 기준정보 (등록/조회)
    // ------------------------------------------------------------------

    public List<McpApiMaster> apis() {
        return masterRepository.findAll();
    }

    public McpApiMaster api(String apiId) {
        return masterRepository.findById(apiId).orElseThrow(() -> new McpApiNotFoundException(apiId));
    }

    /** 새 MCP 도구 등록. 룰 JSON 문법은 등록 시점에 검증해 잘못된 룰이 실행 때 터지지 않게 한다. */
    public McpApiMaster register(McpApiMaster api) {
        validate(api);
        if (masterRepository.findById(api.apiId()).isPresent()) {
            throw new IllegalArgumentException("이미 등록된 MCP API 입니다: " + api.apiId());
        }
        masterRepository.insert(api);
        log.info("[mcp] API 등록 apiId={} direction={} tool={}", api.apiId(), api.direction(), api.toolName());
        return masterRepository.findById(api.apiId()).orElse(api);
    }

    // ------------------------------------------------------------------
    // 실행 (IPO)
    // ------------------------------------------------------------------

    public McpExecutionResult execute(String apiId, Map<String, Object> params, String runId, String requestedBy) {
        // [1] 기준정보 로드 — 등록 테이블이 유일한 진실이다.
        McpApiMaster api = masterRepository.findActive(apiId)
                .orElseThrow(() -> new McpApiNotFoundException(apiId));
        McpExecutionContext ctx = McpExecutionContext.start(api, params, runId, requestedBy);
        ctx.stage("1 기준정보", api.apiId() + " (" + api.direction() + ", " + api.transportType() + ")");

        try {
            // [2] 요청 Reshaping — 내부 로우(INBOUND) 또는 호출 파라미터(OUTBOUND)를 MCP 스펙으로.
            JsonNode request = shapeRequest(api, ctx);
            ctx.requestPayload(request);

            // [3] 통신 — tools/call 한 번.
            McpCallResult call = gateway.call(api, request);
            ctx.call(call);
            ctx.stage("3 통신", api.toolName() + " → " + api.endpointUrl()
                    + (call.fallback() ? " (오프라인 픽스처)" : " [" + call.statusCode() + "]")
                    + " " + call.durationMs() + "ms");
            if (call.httpError()) {
                ctx.warn("MCP 서버가 HTTP " + call.statusCode() + " 로 응답했습니다. raw_payload_out 을 확인하세요.");
            }

            // [4] 응답 Reshaping — 방대한 Raw 를 필요한 필드만 남긴다.
            ctx.responsePayload(shapeResponse(api, call, ctx));
        } catch (RuntimeException e) {
            ctx.fail(e);
            log.warn("[mcp] 실행 실패 apiId={} callId={}: {}", apiId, ctx.getCallId(), e.getMessage());
        }

        // [5] 감사로그 — 성공이든 실패든 한 행을 남긴다.
        long logId = persist(ctx);
        ctx.logId(logId);
        ctx.stage("5 감사로그", "logId=" + logId + ", status=" + ctx.status());
        return toResult(ctx);
    }

    public List<McpPayloadLog> logs(String apiId, int limit) {
        return logRepository.findRecent(apiId, limit);
    }

    // ------------------------------------------------------------------
    // 단계별 세부 (여기부터는 "무슨 일을 하는가" 보다 "어떻게" 의 영역)
    // ------------------------------------------------------------------

    private JsonNode shapeRequest(McpApiMaster api, McpExecutionContext ctx) {
        if (api.direction().readsSourceRows()) {
            List<Map<String, Object>> rows = sourceReader.read(api, ctx.getParams());
            JsonNode raw = objectMapper.valueToTree(rows);
            ctx.rawIn(writeJson(raw));

            ReshapingResult shaped = reshaper.reshape(api.requestReshapingRule(), rows, api.toolName());
            shaped.warnings().forEach(ctx::warn);
            ctx.stage("2 요청 Reshaping", "내부 로우 " + shaped.sourceCount() + "건 → MCP payload 필드 "
                    + shaped.targetCount() + "개" + dropped(shaped));
            return shaped.payload();
        }

        JsonNode raw = objectMapper.valueToTree(ctx.getParams());
        ctx.rawIn(writeJson(raw));
        if (!api.hasRequestRule()) {
            ctx.stage("2 요청 Reshaping", "룰 없음 → 호출 파라미터 그대로 사용");
            return raw;
        }
        ReshapingResult shaped = reshaper.reshape(api.requestReshapingRule(), raw, api.toolName());
        shaped.warnings().forEach(ctx::warn);
        ctx.stage("2 요청 Reshaping", "호출 파라미터 → MCP arguments 필드 " + shaped.targetCount() + "개" + dropped(shaped));
        return shaped.payload();
    }

    private JsonNode shapeResponse(McpApiMaster api, McpCallResult call, McpExecutionContext ctx) {
        if (!api.hasResponseRule()) {
            ctx.stage("4 응답 Reshaping", "룰 없음 → 원본 payload 유지 (" + length(call.rawBody()) + "자)");
            return call.payload();
        }
        ReshapingResult shaped = reshaper.reshape(api.responseReshapingRule(), call.payload(), api.toolName());
        shaped.warnings().forEach(ctx::warn);
        ctx.stage("4 응답 Reshaping", "raw " + length(call.rawBody()) + "자 → 결과 " + shaped.targetCount()
                + "건" + dropped(shaped));
        return shaped.payload();
    }

    private String dropped(ReshapingResult shaped) {
        return shaped.droppedCount() > 0 ? ", 제외 " + shaped.droppedCount() + "건" : "";
    }

    private long persist(McpExecutionContext ctx) {
        McpApiMaster api = ctx.getApi();
        try {
            McpPayloadLog row = new McpPayloadLog(
                    -1,
                    ctx.getCallId(),
                    ctx.getRunId(),
                    api.apiId(),
                    api.direction().name(),
                    truncate(ctx.getRawIn()),
                    truncate(writeJson(ctx.getRequestPayload())),
                    truncate(ctx.getRawOut()),
                    truncate(writeJson(ctx.getResponsePayload())),
                    ctx.getStatusCode(),
                    ctx.durationMs(),
                    errorText(ctx),
                    Instant.now());
            return logRepository.insert(row);
        } catch (RuntimeException e) {
            log.error("[mcp] 감사 로그 저장에 실패했습니다 callId={}: {}", ctx.getCallId(), e.getMessage());
            return -1;
        }
    }

    private String errorText(McpExecutionContext ctx) {
        if (ctx.getErrorMessage() != null) {
            return truncateMessage(ctx.getErrorMessage());
        }
        if (ctx.getCall() != null && ctx.getCall().fallback()) {
            return truncateMessage(ctx.getCall().errorMessage());
        }
        if (ctx.hasHttpError()) {
            return "HTTP " + ctx.getStatusCode();
        }
        return null;
    }

    private McpExecutionResult toResult(McpExecutionContext ctx) {
        McpApiMaster api = ctx.getApi();
        return new McpExecutionResult(
                ctx.getCallId(),
                ctx.getLogId(),
                api.apiId(),
                api.apiName(),
                api.toolName(),
                api.direction(),
                ctx.status(),
                ctx.getStatusCode(),
                ctx.durationMs(),
                ctx.getRequestPayload(),
                ctx.getResponsePayload(),
                List.copyOf(ctx.getStages()),
                List.copyOf(ctx.getWarnings()),
                errorText(ctx));
    }

    private void validate(McpApiMaster api) {
        if (api.apiId() == null || api.apiId().isBlank()) {
            throw new IllegalArgumentException("apiId 는 필수입니다.");
        }
        if (api.toolName() == null || api.toolName().isBlank()) {
            throw new IllegalArgumentException("toolName 은 필수입니다.");
        }
        if (api.endpointUrl() == null || !api.endpointUrl().matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("endpointUrl 은 http(s):// 로 시작해야 합니다.");
        }
        if (api.direction().readsSourceRows() && !api.hasSourceQuery()) {
            throw new IllegalArgumentException(
                    api.direction() + " 방향은 source_query(내부 원천 SELECT)가 필요합니다.");
        }
        if (api.hasRequestRule()) {
            reshaper.parse(api.requestReshapingRule());
        }
        if (api.hasResponseRule()) {
            reshaper.parse(api.responseReshapingRule());
        }
    }

    // ------------------------------------------------------------------
    // 로그 크기 보호
    // ------------------------------------------------------------------

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        int max = Math.max(500, props.getMcp().getMaxLogPayloadChars());
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n...(truncated " + (value.length() - max) + " chars)";
    }

    private String truncateMessage(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private String writeJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
