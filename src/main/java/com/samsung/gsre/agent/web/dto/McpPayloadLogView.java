package com.samsung.gsre.agent.web.dto;

import java.time.Instant;

/** MCP 통신/변환 감사 로그 뷰. raw 와 reshaped 를 나란히 비교할 수 있게 그대로 노출한다. */
public record McpPayloadLogView(
        long logId,
        String callId,
        String runId,
        String apiId,
        String direction,
        Integer statusCode,
        long durationMs,
        String errorMessage,
        Instant createdAt,
        String rawPayloadIn,
        String reshapedPayloadIn,
        String rawPayloadOut,
        String reshapedPayloadOut) {
}
