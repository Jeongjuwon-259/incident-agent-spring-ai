package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.samsung.gsre.agent.common.constant.AgentConstants;
import com.samsung.gsre.agent.common.constant.McpConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.transport.McpCallResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;

/**
 * MCP 실행 1회의 작업대. IPO 단계마다 여기에 결과가 쌓이고, 마지막에 감사 로그 1행이 된다.
 *
 * <p>Orchestrator 는 이 객체만 읽고 고친다. 그래서 흐름 코드에 지역변수가 넘치지 않는다.</p>
 *
 * <h2>IPO 배치</h2>
 * <ul>
 *   <li>상단: 식별자 형식·불변 입력(api/params/runId) → callId 생성</li>
 *   <li>중단: 단계별 결과 적재(stage/rawIn/requestPayload/call/responsePayload/fail)</li>
 *   <li>하단: 상태·소요시간·에러 등 결과 도출</li>
 * </ul>
 */
@Getter
public class McpExecutionContext {

    // ==================================================================
    // 상단 (INPUT) — 기준정보 · 불변 입력
    // ==================================================================

    private final McpApiMaster api;
    private final String callId;
    private final String runId;
    private final String requestedBy;
    private final Map<String, Object> params;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<String> stages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    // 중단 (PROCESS) — 단계별 적재 결과
    private String rawIn;
    private JsonNode requestPayload;
    private String rawOut;
    private JsonNode responsePayload;
    private McpCallResult call;

    // 하단 (OUTPUT) — 상태·결과
    private Integer statusCode;
    private String errorMessage;
    private boolean failed;
    private long logId = McpConstants.LOG_ID_NONE;

    private McpExecutionContext(McpApiMaster api, Map<String, Object> params, String runId, String requestedBy) {
        this.api = api;
        this.params = new LinkedHashMap<>(ValidationUtils.orDefault(params, Map.of()));
        this.runId = runId;
        this.requestedBy = requestedBy;
        this.callId = AgentConstants.MCP_CALL_PREFIX + LocalDateTime.now().format(AgentConstants.ID_TIMESTAMP_FORMAT)
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    public static McpExecutionContext start(McpApiMaster api, Map<String, Object> params, String runId,
                                            String requestedBy) {
        return new McpExecutionContext(api, params, runId, requestedBy);
    }

    // ==================================================================
    // 중단 (PROCESS) — 단계별 결과 적재
    // ==================================================================

    public void stage(String name, String detail) {
        stages.add(detail == null || detail.isBlank() ? name : name + ": " + detail);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public void rawIn(String rawIn) {
        this.rawIn = rawIn;
    }

    public void requestPayload(JsonNode payload) {
        this.requestPayload = payload;
    }

    public void call(McpCallResult result) {
        this.call = result;
        this.rawOut = result.rawBody();
        this.statusCode = result.fallback() ? 0 : result.statusCode();
        if (result.fallback()) {
            warn(result.errorMessage());
        }
    }

    public void responsePayload(JsonNode payload) {
        this.responsePayload = payload;
    }

    public void fail(Throwable error) {
        this.failed = true;
        this.errorMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public void logId(long logId) {
        this.logId = logId;
    }

    // ==================================================================
    // 하단 (OUTPUT) — 상태·소요시간
    // ==================================================================

    public McpExecutionStatus status() {
        if (failed) {
            return McpExecutionStatus.FAILED;
        }
        if (call != null && call.fallback()) {
            return McpExecutionStatus.FALLBACK;
        }
        if (call != null && call.httpError()) {
            return McpExecutionStatus.FAILED;
        }
        return McpExecutionStatus.SUCCESS;
    }

    public boolean hasHttpError() {
        return call != null && call.httpError();
    }

    public long durationMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
