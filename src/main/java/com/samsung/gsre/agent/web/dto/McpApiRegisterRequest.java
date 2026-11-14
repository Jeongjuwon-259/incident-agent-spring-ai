package com.samsung.gsre.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MCP/REST API 등록 요청. 룰은 문자열(JSON) 그대로 받는다 — 테이블 CLOB 과 같은 모양을 유지하기 위함이다.
 * 룰 문법·메서드·헤더 검증은 서비스에서 수행한다(잘못된 값은 400).
 *
 * <p>{@code transportType} 이 REST/REST_API 면 {@code httpMethod}·{@code headersConfig} 가 적용되고,
 * MCP/HTTP/SSE 면 무시된다(항상 tools/call POST).</p>
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
        String createdBy,
        String httpMethod,
        String headersConfig) {
}
