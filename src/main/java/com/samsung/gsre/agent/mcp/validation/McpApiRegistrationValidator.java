package com.samsung.gsre.agent.mcp.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.common.constant.McpConstants;
import com.samsung.gsre.agent.common.constant.SecurityConstants;
import com.samsung.gsre.agent.common.validation.ValidationException;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.reshaping.DataReshaper;
import com.samsung.gsre.agent.security.mask.SensitiveDataMasker;
import com.samsung.gsre.agent.security.net.OutboundTargetBlockedException;
import com.samsung.gsre.agent.security.net.OutboundTargetPolicy;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP/REST 도구 <b>등록 시점</b> 검증기.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>전송 방식은 MCP/HTTP/SSE/REST/REST_API 만, 인증은 NONE/BEARER/API_KEY/BASIC 만 허용한다.</li>
 *   <li>엔드포인트는 {@link OutboundTargetPolicy}(SSRF 정책)를 통과해야 한다.</li>
 *   <li>{@code auth_config} 에는 시크릿 값이 아니라 환경변수 이름만 둔다.</li>
 *   <li>{@code headers_config} 에 인증 헤더·개행 문자를 둘 수 없다. (헤더 주입·평문 시크릿 차단)</li>
 *   <li>Reshaping 룰 JSON 과 원천 SELECT 는 등록 시점에 문법·안전성을 확인한다.</li>
 * </ul>
 *
 * <p>고정 목록은 {@link McpConstants}·{@link SecurityConstants}, 값 검증은 {@link ValidationUtils} 를 쓴다.
 * 이 클래스에는 "무엇을 검증하는가" 만 남기고, 널/공백/형식 판정은 공통 유틸에 맡긴다.</p>
 *
 * <h2>중단 (PROCESS) — 검증 순서</h2>
 * <ol>
 *   <li>식별자·방향·전송 방식</li>
 *   <li>엔드포인트(스킴·호스트·SSRF)</li>
 *   <li>인증 설정(envVar 참조)</li>
 *   <li>커스텀 헤더(이름·값)</li>
 *   <li>Reshaping 룰 문법</li>
 *   <li>REST 메서드·경로변수/본문 규칙</li>
 *   <li>원천 SELECT 읽기 전용 여부</li>
 * </ol>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>통과하면 아무것도 하지 않고, 위반이면 {@link com.samsung.gsre.agent.common.validation.ValidationException}
 * (= IllegalArgumentException) → 400 응답.</p>
 */
@Component
@RequiredArgsConstructor
public class McpApiRegistrationValidator {

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final ObjectMapper mapper;
    private final DataReshaper reshaper;
    private final OutboundTargetPolicy targetPolicy;
    private final SourceQueryValidator sourceQueryValidator;

    // ==================================================================
    // 중단 (PROCESS) — 검증 진입점
    // ==================================================================

    public void validate(McpApiMaster api) {
        ValidationUtils.requirePresent(api, "MCP API 정의가 없습니다.");
        validateIdentity(api);
        validateEndpoint(api);
        validateAuth(api);
        validateHeaders(api);
        validateReshapingRules(api);
        validateRestShape(api);
        validateSourceQuery(api);
    }

    // ==================================================================
    // 하단 (OUTPUT) — 항목별 검증
    // ==================================================================

    private void validateIdentity(McpApiMaster api) {
        ValidationUtils.requireText(api.apiId(), "apiId");
        ValidationUtils.requireText(api.toolName(), "toolName",
                "toolName 은 필수입니다: " + api.apiId());
        String transport = ValidationUtils.requireText(api.transportType(), "transportType",
                "transportType 값이 필요합니다: " + api.apiId()).toUpperCase(Locale.ROOT);
        ValidationUtils.requireOneOf(transport, McpConstants.TRANSPORT_TYPES,
                "지원하지 않는 transport_type 입니다: " + api.transportType()
                        + " (" + McpConstants.TRANSPORT_TYPES_LABEL + ") — apiId=" + api.apiId());
    }

    private void validateEndpoint(McpApiMaster api) {
        String endpoint = ValidationUtils.requireText(api.endpointUrl(), "endpointUrl",
                "endpointUrl 은 필수입니다: " + api.apiId());
        try {
            targetPolicy.verify(endpoint);
        } catch (OutboundTargetBlockedException e) {
            throw new OutboundTargetBlockedException(
                    "endpointUrl 이 네트워크 정책을 통과하지 못했습니다: " + e.getMessage() + " — apiId=" + api.apiId());
        }
    }

    private void validateAuth(McpApiMaster api) {
        String authType = ValidationUtils.requireText(api.authType(), "authType",
                "authType 은 필수입니다: " + api.apiId()).toUpperCase(Locale.ROOT);
        ValidationUtils.requireOneOf(authType, McpConstants.AUTH_TYPES,
                "지원하지 않는 auth_type 입니다: " + api.authType() + " ("
                        + McpConstants.AUTH_TYPES_LABEL + ") — apiId=" + api.apiId());

        boolean hasConfig = ValidationUtils.hasText(api.authConfig());
        if (McpConstants.AUTH_NONE.equals(authType)) {
            ValidationUtils.requireTrue(!hasConfig,
                    "authType=NONE 이면 authConfig 를 비워야 합니다: " + api.apiId());
            return;
        }
        ValidationUtils.requireTrue(hasConfig,
                "authType=" + authType + " 에는 authConfig({\"envVar\":\"...\"}) 가 필요합니다: " + api.apiId());

        JsonNode config = readObject(api.authConfig(), "authConfig", api.apiId());
        for (Iterator<Map.Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            ValidationUtils.requireTrue(
                    !SecurityConstants.AUTH_CONFIG_LITERAL_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT)),
                    "authConfig 에 시크릿 값을 직접 넣을 수 없습니다(키: " + field.getKey()
                            + "). 환경변수 이름(envVar)만 두세요: " + api.apiId());
        }
        String envVar = config.path(McpConstants.AUTH_CONFIG_ENV_VAR).asText("");
        ValidationUtils.requirePattern(envVar, ENV_NAME,
                "authConfig.envVar 는 환경변수 이름이어야 합니다(예: MCP_K8S_TOKEN): " + api.apiId());
        if (config.hasNonNull(McpConstants.AUTH_CONFIG_HEADER)) {
            ValidationUtils.requirePattern(config.get(McpConstants.AUTH_CONFIG_HEADER).asText(""), HEADER_NAME,
                    "authConfig.header 가 올바른 헤더 이름이 아닙니다: " + api.apiId());
        }
    }

    private void validateHeaders(McpApiMaster api) {
        if (!api.hasHeadersConfig()) {
            return;
        }
        JsonNode config = readObject(api.headersConfig(), "headersConfig", api.apiId());
        for (Iterator<Map.Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = field.getKey();
            ValidationUtils.requirePattern(name, HEADER_NAME,
                    "헤더 이름이 올바르지 않습니다: " + name + " — apiId=" + api.apiId());
            ValidationUtils.requireTrue(!SensitiveDataMasker.isSensitiveKey(name),
                    "headersConfig 에 인증/시크릿 헤더(" + name + ")를 둘 수 없습니다. "
                            + "authType/authConfig(envVar)를 사용하세요 — apiId=" + api.apiId());
            JsonNode value = field.getValue();
            ValidationUtils.requireTrue(value != null && !value.isNull() && !value.isContainerNode(),
                    "헤더 값은 문자열/숫자여야 합니다: " + name + " — apiId=" + api.apiId());
            String text = value.asText();
            ValidationUtils.requireTrue(!text.contains("\r") && !text.contains("\n"),
                    "헤더 값에 개행을 넣을 수 없습니다: " + name + " — apiId=" + api.apiId());
        }
    }

    private void validateReshapingRules(McpApiMaster api) {
        if (api.hasRequestRule()) {
            reshaper.parse(api.requestReshapingRule());
        }
        if (api.hasResponseRule()) {
            reshaper.parse(api.responseReshapingRule());
        }
    }

    private void validateRestShape(McpApiMaster api) {
        if (!api.isRestTransport()) {
            return;
        }
        var method = api.httpMethodValue(); // 잘못된 메서드면 여기서 IllegalArgumentException(400)
        if (api.direction().readsSourceRows()) {
            ValidationUtils.requireTrue(method.hasBody(),
                    method + " 메서드는 본문을 보내지 않으므로 " + api.direction()
                            + " 방향에 쓸 수 없습니다. POST/PUT/PATCH 중 하나를 지정하세요: " + api.apiId());
        }
    }

    private void validateSourceQuery(McpApiMaster api) {
        if (api.direction().readsSourceRows()) {
            ValidationUtils.requireTrue(api.hasSourceQuery(),
                    api.direction() + " 방향은 source_query(내부 원천 SELECT)가 필요합니다: " + api.apiId());
        }
        if (api.hasSourceQuery()) {
            sourceQueryValidator.validate(api);
        }
    }

    /** JSON 객체 해석. 문법 오류는 검증 예외(400)로 변환한다. */
    private JsonNode readObject(String json, String field, String apiId) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new ValidationException(field + " JSON 을 해석하지 못했습니다: " + apiId);
        }
        ValidationUtils.requireTrue(node != null && node.isObject(),
                field + " 는 JSON 객체여야 합니다: " + apiId);
        return node;
    }
}
