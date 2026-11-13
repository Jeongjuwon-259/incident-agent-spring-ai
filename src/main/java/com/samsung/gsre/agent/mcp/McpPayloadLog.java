package com.samsung.gsre.agent.mcp;

import java.time.Instant;

/**
 * MCP 통신/변환 감사 로그 1행 ({@code svcm_tb_mcp_payload_log}).
 *
 * <p>한 번의 실행(callId)마다 "무엇을 받아서(raw in) 어떻게 바꿔 보냈고(reshaped in),
 * 무엇을 받아(raw out) 어떻게 정제했는지(reshaped out)" 를 남긴다.
 * 장애 때 원본과 가공본을 나란히 비교할 수 있는 것이 이 테이블의 목적이다.</p>
 */
public record McpPayloadLog(
        long logId,
        String callId,
        String runId,
        String apiId,
        String direction,
        String rawPayloadIn,
        String reshapedPayloadIn,
        String rawPayloadOut,
        String reshapedPayloadOut,
        Integer statusCode,
        long durationMs,
        String errorMessage,
        Instant createdAt) {
}
