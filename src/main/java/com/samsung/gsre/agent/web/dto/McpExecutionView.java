package com.samsung.gsre.agent.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * MCP/REST 실행 응답. 단계 이력(stages)으로 "어디서 무엇이 일어났는지" 를 그대로 보여준다.
 * transportType/httpMethod 로 이번 실행이 어떤 프로토콜로 나갔는지 바로 알 수 있다.
 */
public record McpExecutionView(
        String callId,
        long logId,
        String apiId,
        String apiName,
        String toolName,
        String direction,
        String status,
        Integer statusCode,
        long durationMs,
        JsonNode request,
        JsonNode response,
        List<String> stages,
        List<String> warnings,
        String error,
        String transportType,
        String httpMethod) {
}
