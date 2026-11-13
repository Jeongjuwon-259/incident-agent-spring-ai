package com.samsung.gsre.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MCP API 등록 요청. 룰은 문자열(JSON) 그대로 받는다 — 테이블 CLOB 과 같은 모양을 유지하기 위함이다.
 * 룰 문법 검증은 서비스에서 수행한다(잘못된 룰은 400).
 */
public record McpApiRegisterRequest(
        @NotBlank(message = "apiId 는 필수입니다.") String apiId,
        @NotBlank(message = "apiName 은 필수입니다.") String apiName,
        @NotBlank(message = "toolName 은 필수입니다.") String toolName,
        @NotBlank(message = "direction 은 필수입니다. (OUTBOUND/INBOUND/BIDIRECTIONAL)") String direction,
        String transportType,
        @NotBlank(message = "endpointUrl 은 필수입니다.") String endpointUrl,
        String authType,
        String authConfig,
        String requestSchema,
        String requestReshapingRule,
        String responseReshapingRule,
        String sourceQuery,
        Integer timeoutMs,
        Boolean active,
        String description,
        String createdBy) {
}
