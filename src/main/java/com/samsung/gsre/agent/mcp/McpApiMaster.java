package com.samsung.gsre.agent.mcp;

import com.samsung.gsre.agent.common.constant.AgentConstants;
import com.samsung.gsre.agent.common.constant.McpConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.transport.McpClientGateway;
import com.samsung.gsre.agent.mcp.transport.McpHttpMethod;
import com.samsung.gsre.agent.mcp.transport.RestApiClientGateway;
import java.time.Instant;
import java.util.Locale;

/**
 * MCP/REST API 등록 마스터 1행 ({@code svcm_tb_mcp_api_master}).
 *
 * <p>이 레코드가 곧 "코드 없는 도구 정의" 다. 방향·프로토콜·엔드포인트·HTTP 메서드·헤더·인증·
 * 요청/응답 Reshaping 룰·원천 SELECT 가 모두 테이블에서 온다. 새 도구를 추가할 때 배포는 필요 없다.</p>
 *
 * <p>프로토콜은 {@code transportType} 하나로 갈린다.</p>
 * <ul>
 *   <li>{@code MCP} / {@code HTTP} / {@code SSE} — MCP Streamable HTTP (JSON-RPC {@code tools/call}) → {@link McpClientGateway}</li>
 *   <li>{@code REST} / {@code REST_API} — 순수 REST API (JSON-RPC 봉투 없음) → {@link RestApiClientGateway}</li>
 * </ul>
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
        Instant updatedAt,
        String httpMethod,
        String headersConfig) {

    public McpApiMaster {
        direction = direction == null ? McpDirection.OUTBOUND : direction;
        transportType = blankTo(transportType, McpConstants.TRANSPORT_HTTP);
        authType = blankTo(authType, McpConstants.AUTH_NONE);
        httpMethod = McpHttpMethod.from(httpMethod).name();
        timeoutMs = timeoutMs <= 0 ? AgentConstants.DEFAULT_MCP_TIMEOUT_MS : timeoutMs;
    }

    /**
     * http_method / headers_config 도입 전 시그니처. 기존 코드·테스트와의 호환을 위해 남긴다.
     * (메서드는 POST, 커스텀 헤더는 없음 = MCP 기본 동작과 동일)
     */
    public McpApiMaster(String apiId, String apiName, String toolName, McpDirection direction,
                        String transportType, String endpointUrl, String authType, String authConfig,
                        String requestSchema, String requestReshapingRule, String responseReshapingRule,
                        String sourceQuery, int timeoutMs, boolean active, String description,
                        String createdBy, Instant createdAt, Instant updatedAt) {
        this(apiId, apiName, toolName, direction, transportType, endpointUrl, authType, authConfig,
                requestSchema, requestReshapingRule, responseReshapingRule, sourceQuery, timeoutMs, active,
                description, createdBy, createdAt, updatedAt, null, null);
    }

    /** REST/REST_API 만 순수 REST 게이트웨이로 간다. 나머지는 MCP tools/call. */
    public boolean isRestTransport() {
        return McpConstants.REST_TRANSPORTS.contains(transportType);
    }

    public McpHttpMethod httpMethodValue() {
        return McpHttpMethod.from(httpMethod);
    }

    /** 단계 이력에 찍을 전송 방식. REST 는 메서드, MCP 는 tools/call 한 가지뿐이다. */
    public String protocolLabel() {
        return isRestTransport() ? httpMethod : "tools/call";
    }

    public boolean hasHeadersConfig() {
        return ValidationUtils.hasText(headersConfig);
    }

    public boolean hasRequestRule() {
        return ValidationUtils.hasText(requestReshapingRule);
    }

    public boolean hasResponseRule() {
        return ValidationUtils.hasText(responseReshapingRule);
    }

    public boolean hasSourceQuery() {
        return ValidationUtils.hasText(sourceQuery);
    }

    private static String blankTo(String value, String fallback) {
        return ValidationUtils.isBlank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
