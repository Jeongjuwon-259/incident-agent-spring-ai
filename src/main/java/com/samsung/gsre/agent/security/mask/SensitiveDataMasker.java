package com.samsung.gsre.agent.security.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.samsung.gsre.agent.common.constant.SecurityConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그·감사 테이블에 남기기 전에 시크릿과 개인정보를 지우는 마스커.
 *
 * <h2>상단 (INPUT) — 판정 기준정보</h2>
 * <ul>
 *   <li>키 이름이 시크릿이면 값 전체를 {@code ***} 로 바꾼다. (예: apiKey, accessToken, password)</li>
 *   <li>본문에 섞인 토큰·이메일·전화번호·주민등록번호는 패턴으로 찾아 부분 마스킹한다.</li>
 *   <li>JSON 이면 구조를 먼저 보고, 구조가 아니면 텍스트 규칙만 적용한다.</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 마스킹</h2>
 * <p>문자열 → (키 기반) JSON 순회 → (패턴 기반) 텍스트 치환.</p>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>마스킹된 문자열. 값이 바뀌지 않았으면 원본 문자열을 그대로 돌려준다(로그 형식 보존).</p>
 *
 * <p>이 클래스는 <b>읽기 전용 사본</b>만 만든다. 호출자가 가진 원본 payload 는 바꾸지 않는다.
 * 저장 위치: {@code svcm_tb_mcp_payload_log}, {@code agent_llm_call}, {@code agent_run_step},
 * 그리고 애플리케이션 로그에 찍히는 URL/오류 메시지.</p>
 */
public final class SensitiveDataMasker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}");

    /** key=value / "key": "value" 형태의 시크릿. 키 이름에 token/secret/api_key/password 가 들어간 경우만. */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\"?)([A-Za-z0-9_.-]*(?:password|passwd|secret|token|api[_-]?key|apikey|authorization|credential|cookie)[A-Za-z0-9_.-]*)(\"?)"
                    + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]]+)");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern RRN = Pattern.compile("\\b\\d{6}-?\\d{7}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b(01[016789]|02|0[3-6][0-9])[-. ]?\\d{3,4}[-. ]?\\d{4}\\b");

    private SensitiveDataMasker() {
    }

    // ==================================================================
    // 상단 (INPUT) — 키 판정
    // ==================================================================

    /** 헤더/필드 이름이 시크릿을 담는 자리인지. */
    public static boolean isSensitiveKey(String key) {
        if (ValidationUtils.isBlank(key)) {
            return false;
        }
        String normalized = normalizeKey(key);
        if (SecurityConstants.SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        for (String suffix : SecurityConstants.SENSITIVE_KEY_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").trim();
    }

    // ==================================================================
    // 중단 (PROCESS) — 마스킹
    // ==================================================================

    /** 텍스트 안의 토큰·이메일·전화번호·주민등록번호를 마스킹한다. */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = AUTH_SCHEME.matcher(text).replaceAll("$1 " + SecurityConstants.MASK);
        masked = SECRET_PAIR.matcher(masked).replaceAll(match ->
                match.group(1) + match.group(2) + match.group(3) + match.group(4) + maskedValue(match.group(5)));
        masked = maskEmails(masked);
        masked = RRN.matcher(masked).replaceAll("******-*******");
        masked = maskPhones(masked);
        return masked;
    }

    /**
     * JSON 문자열이면 키 기반으로, 아니면 텍스트 규칙으로 마스킹한다.
     * 중첩 객체/배열 안의 시크릿도 같은 규칙으로 처리한다.
     */
    public static String maskJson(String raw) {
        if (ValidationUtils.isBlank(raw)) {
            return raw;
        }
        String textMasked = maskText(raw);
        JsonNode root = parse(textMasked);
        if (root == null) {
            return textMasked;
        }
        boolean[] changed = {false};
        JsonNode masked = maskNode(root, changed);
        if (!changed[0]) {
            return textMasked;
        }
        try {
            return MAPPER.writeValueAsString(masked);
        } catch (Exception e) {
            return textMasked;
        }
    }

    /** 헤더 설정(JSON 객체)을 뷰/로그에 내보낼 때 시크릿 헤더만 가린다. */
    public static String maskHeaders(String headersJson) {
        return maskJson(headersJson);
    }

    /**
     * JSON 노드를 복사해 마스킹한 새 노드를 돌려준다. (원본은 바꾸지 않는다)
     * API 응답으로 payload 를 그대로 돌려줄 때 사용한다.
     */
    public static JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode copy = node.deepCopy();
        maskNode(copy, new boolean[1]);
        return copy;
    }

    private static JsonNode maskNode(JsonNode node, boolean[] changed) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (isSensitiveKey(field.getKey())) {
                    object.set(field.getKey(), TextNode.valueOf(SecurityConstants.MASK));
                    changed[0] = true;
                } else if (value != null && value.isTextual()) {
                    String masked = maskText(value.asText());
                    if (!masked.equals(value.asText())) {
                        object.set(field.getKey(), TextNode.valueOf(masked));
                        changed[0] = true;
                    }
                } else if (value != null && (value.isObject() || value.isArray())) {
                    maskNode(value, changed);
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value != null && value.isTextual()) {
                    String masked = maskText(value.asText());
                    if (!masked.equals(value.asText())) {
                        array.set(i, TextNode.valueOf(masked));
                        changed[0] = true;
                    }
                } else {
                    maskNode(value, changed);
                }
            }
        }
        return node;
    }

    /** 값의 따옴표 스타일을 유지해 JSON/쿼리 문자열이 깨지지 않게 한다. */
    private static String maskedValue(String rawValue) {
        if (rawValue == null) {
            return SecurityConstants.MASK;
        }
        if (rawValue.startsWith("\"")) {
            return "\"" + SecurityConstants.MASK + "\"";
        }
        if (rawValue.startsWith("'")) {
            return "'" + SecurityConstants.MASK + "'";
        }
        return SecurityConstants.MASK;
    }

    private static String maskEmails(String text) {
        Matcher matcher = EMAIL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String email = matcher.group();
            int at = email.indexOf('@');
            String local = email.substring(0, at);
            String visible = local.length() <= 2 ? local.substring(0, Math.min(1, local.length())) : local.substring(0, 2);
            matcher.appendReplacement(out, Matcher.quoteReplacement(visible + "***" + email.substring(at)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskPhones(String text) {
        Matcher matcher = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[^0-9]", "");
            if (digits.length() < 9) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String masked = digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
            matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ==================================================================
    // 하단 (OUTPUT) — 내부 유틸
    // ==================================================================

    private static JsonNode parse(String text) {
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }
}
