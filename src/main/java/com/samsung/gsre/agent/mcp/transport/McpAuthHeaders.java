package com.samsung.gsre.agent.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.common.constant.McpConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 인증 헤더 해석. MCP 게이트웨이와 REST 게이트웨이가 같은 규칙을 쓰도록 한 곳에 모았다.
 *
 * <p>보안 원칙: {@code auth_config} 에는 시크릿이 아니라 <b>환경변수 이름</b>만 있다.
 * 값은 프로세스 환경에서 읽고, 로그·감사 payload 어디에도 쓰지 않는다. 환경변수가 없으면
 * 요청을 실패시키지 않고 헤더만 생략한다(로컬 데모에서 인증 없이 흐름을 볼 수 있게).</p>
 */
@Slf4j
final class McpAuthHeaders {

    private McpAuthHeaders() {
    }

    /** @return 헤더명 → 값. 인증이 없거나 설정을 해석할 수 없으면 빈 맵. */
    static Map<String, String> resolve(McpApiMaster api, ObjectMapper mapper) {
        Map<String, String> headers = new LinkedHashMap<>();
        String authType = api.authType();
        if (authType == null || McpConstants.AUTH_NONE.equalsIgnoreCase(authType)
                || ValidationUtils.isBlank(api.authConfig())) {
            return headers;
        }
        try {
            JsonNode config = mapper.readTree(api.authConfig());
            String envVar = config.path(McpConstants.AUTH_CONFIG_ENV_VAR).asText("");
            String secret = envVar.isBlank() ? "" : System.getenv(envVar);
            if (ValidationUtils.isBlank(secret)) {
                log.warn("[transport] 인증 환경변수가 없어 헤더를 생략합니다 apiId={} envVar={}", api.apiId(), envVar);
                return headers;
            }
            switch (authType.trim().toUpperCase(Locale.ROOT)) {
                case McpConstants.AUTH_BEARER -> headers.put(McpConstants.HEADER_AUTHORIZATION,
                        McpConstants.AUTH_SCHEME_BEARER + secret);
                case McpConstants.AUTH_API_KEY -> headers.put(
                        config.path(McpConstants.AUTH_CONFIG_HEADER).asText(McpConstants.HEADER_DEFAULT_API_KEY), secret);
                case McpConstants.AUTH_BASIC -> headers.put(McpConstants.HEADER_AUTHORIZATION,
                        McpConstants.AUTH_SCHEME_BASIC + Base64.getEncoder().encodeToString(secret.getBytes()));
                default -> log.warn("[transport] 알 수 없는 auth_type={} apiId={}", authType, api.apiId());
            }
        } catch (Exception e) {
            log.warn("[transport] auth_config 해석 실패 apiId={}: {}", api.apiId(), e.getMessage());
        }
        return headers;
    }

    /** headers_config(JSON 객체) → 헤더 맵. 값은 문자열로 쓴다. */
    static Map<String, String> customHeaders(McpApiMaster api, ObjectMapper mapper) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!api.hasHeadersConfig()) {
            return headers;
        }
        try {
            JsonNode config = mapper.readTree(api.headersConfig());
            if (config == null || !config.isObject()) {
                log.warn("[transport] headers_config 는 JSON 객체여야 합니다 apiId={}", api.apiId());
                return headers;
            }
            config.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !value.isNull()) {
                    headers.put(entry.getKey(), value.asText());
                }
            });
        } catch (Exception e) {
            log.warn("[transport] headers_config 해석 실패 apiId={}: {}", api.apiId(), e.getMessage());
        }
        return headers;
    }
}
