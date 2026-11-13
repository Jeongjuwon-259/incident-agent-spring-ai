package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 */
@Getter
public class McpExecutionContext {

    private final McpApiMaster api;
    private final String callId;
    private final String runId;
    private final String requestedBy;
    private final Map<String, Object> params;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<String> stages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private String rawIn;
    private JsonNode requestPayload;
    private String rawOut;
    private JsonNode responsePayload;
    private McpCallResult call;
    private Integer statusCode;
    private String errorMessage;
    private boolean failed;
    private long logId = -1;

    private static final DateTimeFormatter CALL_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private McpExecutionContext(McpApiMaster api, Map<String, Object> params, String runId, String requestedBy) {
        this.api = api;
        this.params = params == null ? Map.of() : new LinkedHashMap<>(params);
        this.runId = runId;
        this.requestedBy = requestedBy;
        this.callId = "MCP-" + LocalDateTime.now().format(CALL_ID_FORMAT)
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    public static McpExecutionContext start(McpApiMaster api, Map<String, Object> params, String runId,
                                            String requestedBy) {
        return new McpExecutionContext(api, params, runId, requestedBy);
    }

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
