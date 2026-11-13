package com.samsung.gsre.agent.mcp;

import java.time.Instant;

/**
 * MCP API 등록 마스터 1행 ({@code svcm_tb_mcp_api_master}).
 *
 * <p>이 레코드가 곧 "코드 없는 도구 정의" 다. 방향·엔드포인트·인증·요청/응답 Reshaping 룰·
 * 원천 SELECT 가 모두 테이블에서 온다. 새 도구를 추가할 때 배포는 필요 없다.</p>
 *
 * <p>보안: {@code authConfig} 에는 시크릿 값이 아니라 시크릿을 담은 <b>환경변수 이름</b>만 둔다.</p>
 */
public record McpApiMaster(
        String apiId,
        String apiName,
        String toolName,
        McpDirection direction,
        String transportType,
        String endpointUrl,
        String authType,
        String authConfig,
        String requestSchema,
        String requestReshapingRule,
        String responseReshapingRule,
        String sourceQuery,
        int timeoutMs,
        boolean active,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public McpApiMaster {
        direction = direction == null ? McpDirection.OUTBOUND : direction;
        transportType = blankTo(transportType, "HTTP");
        authType = blankTo(authType, "NONE");
        timeoutMs = timeoutMs <= 0 ? 5_000 : timeoutMs;
    }

    public boolean hasRequestRule() {
        return requestReshapingRule != null && !requestReshapingRule.isBlank();
    }

    public boolean hasResponseRule() {
        return responseReshapingRule != null && !responseReshapingRule.isBlank();
    }

    public boolean hasSourceQuery() {
        return sourceQuery != null && !sourceQuery.isBlank();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }
}
