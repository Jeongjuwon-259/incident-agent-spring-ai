package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * MCP 실행 응답. "무엇을 보냈고(request) 무엇을 돌려받았는지(response)" 만 남기고,
 * 원본(raw)은 감사 로그에서 본다.
 *
 * @param request  Reshaping 을 거친 MCP 요청 payload (INBOUND 면 DB 로우가 변환된 결과)
 * @param response Reshaping 을 거친 최종 응답 payload (OUTBOUND 의 정제 결과)
 * @param stages   IPO 단계 이력 (기준정보 → 요청가공 → 통신 → 응답가공 → 감사로그)
 */
public record McpExecutionResult(
        String callId,
        long logId,
        String apiId,
        String apiName,
        String toolName,
        McpDirection direction,
        McpExecutionStatus status,
        Integer statusCode,
        long durationMs,
        JsonNode request,
        JsonNode response,
        List<String> stages,
        List<String> warnings,
        String errorMessage) {
}
