package com.samsung.gsre.agent.mcp.repository;

import java.time.Instant;

/**
 * MCP/REST 통신·변환 감사 로그 1행 ({@code svcm_tb_mcp_payload_log}).
 *
 * <p>한 번의 실행(callId)마다 "무엇을 받아서(raw in) 어떻게 바꿔 보냈고(reshaped in),
 * 무엇을 받아(raw out) 어떻게 정제했는지(reshaped out)" 를 남긴다.
 * 장애 때 원본과 가공본을 나란히 비교할 수 있는 것이 이 테이블의 목적이다.</p>
 *
 * <p>{@code transportType}/{@code httpMethod} 는 실행에 실제로 쓰인 프로토콜과 메서드다.
 * 같은 API 가 MCP 로도 REST 로도 등록될 수 있으므로, 실패 원인을 볼 때 이 값부터 확인한다.</p>
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
        Instant createdAt,
        String transportType,
        String httpMethod) {

    /** transport_type / http_method 도입 전 시그니처(호환용). */
    public McpPayloadLog(long logId, String callId, String runId, String apiId, String direction,
                         String rawPayloadIn, String reshapedPayloadIn, String rawPayloadOut,
                         String reshapedPayloadOut, Integer statusCode, long durationMs,
                         String errorMessage, Instant createdAt) {
        this(logId, callId, runId, apiId, direction, rawPayloadIn, reshapedPayloadIn, rawPayloadOut,
                reshapedPayloadOut, statusCode, durationMs, errorMessage, createdAt, null, null);
    }
}
