package com.samsung.gsre.agent.mcp.reshaping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.exception.ReshapingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 양방향 Data Reshaping 엔진.
 *
 * <p>하는 일은 하나다. <b>룰(JSONPath 매핑)을 읽고, 원본 JSON 을 다른 JSON 으로 다시 짠다.</b>
 * OUTBOUND(외부 응답 → 에이전트 컨텍스트)과 INBOUND(DB 로우 → MCP 스펙)은 방향만 다르고
 * 변환 자체는 같으므로 엔진도 하나다.</p>
 *
 * <p>설계 의도:</p>
 * <ul>
 *   <li>코드에 필드명을 박지 않는다. 새 시스템 연동은 룰 한 줄 추가로 끝난다.</li>
 *   <li>원본은 절대 변형하지 않는다. 항상 새 노드를 만든다. (감사 로그의 raw 와 비교 가능)</li>
 *   <li>원천이 비었을 때의 정책(required/default/dropNull)을 룰이 정한다.</li>
 *   <li>룰 오류는 조용히 넘기지 않고 어느 필드의 어느 경로인지 알려준다.</li>
 * </ul>
 */
@Component
public class DataReshaper {

    // ==================================================================
    // 상단 (INPUT) — 기준정보(JSONPath 설정)와 의존성
    // ==================================================================

    private final ObjectMapper mapper;
    private final Configuration jsonPathConfig;

    public DataReshaper(ObjectMapper mapper) {
        this.mapper = mapper;
        this.jsonPathConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(mapper))
                .mappingProvider(new JacksonMappingProvider(mapper))
                .options(Option.SUPPRESS_EXCEPTIONS)
                .build();
    }

    // ==================================================================
    // 중단 (PROCESS) — 룰 파싱 → 방향별 매핑
    // ==================================================================

    public ReshapingRule parse(String ruleJson) {
        return ReshapingRule.parse(ruleJson);
    }

    /** 룰 문자열을 그대로 받는 진입점. 룰이 비면 원본을 그대로 돌려준다(경고 1건). */
    public ReshapingResult reshape(String ruleJson, Object raw, String fallbackToolName) {
        if (ValidationUtils.isBlank(ruleJson)) {
            return new ReshapingResult(toNode(raw), 1, 1, 0,
                    List.of("Reshaping 룰이 없어 원본 payload 를 그대로 사용했습니다."));
        }
        return reshape(ReshapingRule.parse(ruleJson), raw, fallbackToolName);
    }

    public ReshapingResult reshape(ReshapingRule rule, Object raw, String fallbackToolName) {
        JsonNode source = toNode(raw);
        List<String> warnings = new ArrayList<>();

        JsonNode root = read(source, rule.rootPath(), warnings);
        if (root == null) {
            root = source;
            warnings.add("rootPath 를 찾지 못해 원본 루트를 사용했습니다: " + rule.rootPath());
        }

        JsonNode payload;
        int targetCount;
        int dropped = 0;
        int sourceCount;

        if (rule.mode() == ReshapingRule.Mode.COLLECTION) {
            List<JsonNode> items = asList(read(root, rule.itemPath(), warnings));
            sourceCount = items.size();
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : items) {
                if (!passesFilters(item, rule.filters(), warnings)) {
                    dropped++;
                    continue;
                }
                out.add(mapObject(item, rule.fields(), rule.dropNull(), warnings));
            }
            if (rule.limit() != null && rule.limit() > 0 && out.size() > rule.limit()) {
                dropped += out.size() - rule.limit();
                while (out.size() > rule.limit()) {
                    out.remove(out.size() - 1);
                }
            }
            targetCount = out.size();
            payload = out;
        } else {
            sourceCount = 1;
            ObjectNode mapped = mapObject(root, rule.fields(), rule.dropNull(), warnings);
            targetCount = mapped.size();
            payload = mapped;
        }

        payload = wrapEnvelope(rule.envelope(), payload, fallbackToolName);
        return new ReshapingResult(payload, sourceCount, targetCount, dropped, List.copyOf(warnings));
    }

    // ==================================================================
    // 하단 (OUTPUT) — 매핑·변환·필터·JSON 결과 유틸
    // ==================================================================


    private ObjectNode mapObject(JsonNode scope, List<ReshapingRule.FieldRule> fields, boolean dropNull,
                                 List<String> warnings) {
        ObjectNode out = mapper.createObjectNode();
        for (ReshapingRule.FieldRule field : fields) {
            JsonNode value = field.constant() != null
                    ? field.constant()
                    : read(scope, field.source(), warnings);

            value = applyTransform(value, field.transform(), warnings, field.target());
            value = applyAggregate(value, field.aggregate(), field.defaultValue());

            if (isMissing(value)) {
                if (field.required()) {
                    throw new ReshapingException("필수 필드 '" + field.target()
                            + "' 의 원천을 찾지 못했습니다: " + field.source());
                }
                value = field.defaultValue();
            }
            if (isMissing(value)) {
                if (dropNull) {
                    continue;
                }
                out.set(field.target(), NullNode.instance);
                continue;
            }
            out.set(field.target(), value);
        }
        return out;
    }

    private JsonNode wrapEnvelope(ReshapingRule.Envelope envelope, JsonNode payload, String fallbackToolName) {
        if (envelope == null) {
            return payload;
        }
        if (envelope.restJson()) {
            return wrapRestJson(envelope, payload);
        }
        if (!envelope.toolCall()) {
            return payload;
        }
        String tool = envelope.tool() == null || envelope.tool().isBlank() ? fallbackToolName : envelope.tool();
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("tool", tool);
        if (envelope.argumentsKey() == null || envelope.argumentsKey().isBlank()) {
            wrapped.set("arguments", payload);
        } else {
            ObjectNode arguments = mapper.createObjectNode();
            arguments.set(envelope.argumentsKey(), payload);
            wrapped.set("arguments", arguments);
        }
        return wrapped;
    }

    /**
     * 순수 REST JSON 본문: 고정 필드(attributes) 먼저, 그 다음 배열/객체를 payloadKey 로 넣는다.
     * JSON-RPC 봉투가 없으므로 외부 시스템은 평범한 REST 요청으로 받는다.
     */
    private JsonNode wrapRestJson(ReshapingRule.Envelope envelope, JsonNode payload) {
        ObjectNode body = mapper.createObjectNode();
        if (envelope.attributes() != null && envelope.attributes().isObject()) {
            body.setAll((ObjectNode) envelope.attributes());
        }
        String key = ValidationUtils.firstNonBlank(envelope.payloadKey(), envelope.argumentsKey(), "payload");
        body.set(key, payload);
        return body;
    }

    // ------------------------------------------------------------------
    // 값 변환
    // ------------------------------------------------------------------

    private JsonNode applyTransform(JsonNode value, ReshapingRule.Transform transform, List<String> warnings,
                                    String target) {
        if (isMissing(value) || transform == null || transform == ReshapingRule.Transform.NONE) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode element : value) {
                JsonNode converted = convertScalar(element, transform, warnings, target);
                if (!isMissing(converted)) {
                    out.add(converted);
                }
            }
            return out.isEmpty() ? NullNode.instance : out;
        }
        return convertScalar(value, transform, warnings, target);
    }

    private JsonNode convertScalar(JsonNode value, ReshapingRule.Transform transform, List<String> warnings,
                                   String target) {
        try {
            return switch (transform) {
                case TRIM -> mapper.getNodeFactory().textNode(value.asText().trim());
                case UPPER -> mapper.getNodeFactory().textNode(value.asText().toUpperCase(Locale.ROOT));
                case LOWER -> mapper.getNodeFactory().textNode(value.asText().toLowerCase(Locale.ROOT));
                case TO_STRING -> value.isTextual() ? value : mapper.getNodeFactory().textNode(value.asText());
                case TO_NUMBER -> mapper.getNodeFactory().numberNode(new BigDecimal(value.asText().replace(",", "").trim()));
                case TO_INT -> mapper.getNodeFactory().numberNode(
                        new BigDecimal(value.asText().replace(",", "").trim()).intValue());
                case TO_BOOLEAN -> mapper.getNodeFactory().booleanNode(parseBoolean(value.asText()));
                case NONE -> value;
            };
        } catch (RuntimeException e) {
            warnings.add("필드 '" + target + "' 변환 실패(" + transform + "): " + value.asText());
            return NullNode.instance;
        }
    }

    private boolean parseBoolean(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "y".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized);
    }

    private JsonNode applyAggregate(JsonNode value, ReshapingRule.Aggregate aggregate, JsonNode defaultValue) {
        if (aggregate == null) {
            return value;
        }
        List<BigDecimal> numbers = new ArrayList<>();
        collectNumbers(value, numbers);
        return switch (aggregate) {
            case COUNT -> mapper.getNodeFactory().numberNode(isMissing(value) ? 0 : countScalars(value));
            case SUM -> mapper.getNodeFactory().numberNode(numbers.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros());
            case AVG -> numbers.isEmpty() ? defaultValue : mapper.getNodeFactory().numberNode(
                    numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(numbers.size()), 2, RoundingMode.HALF_UP));
            case MIN -> numbers.isEmpty() ? defaultValue
                    : mapper.getNodeFactory().numberNode(numbers.stream().min(BigDecimal::compareTo).orElseThrow());
            case MAX -> numbers.isEmpty() ? defaultValue
                    : mapper.getNodeFactory().numberNode(numbers.stream().max(BigDecimal::compareTo).orElseThrow());
        };
    }

    private void collectNumbers(JsonNode node, List<BigDecimal> out) {
        if (isMissing(node)) {
            return;
        }
        if (node.isNumber()) {
            out.add(node.decimalValue());
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectNumbers(child, out));
            return;
        }
        if (node.isObject()) {
            node.forEach(child -> collectNumbers(child, out));
            return;
        }
        try {
            out.add(new BigDecimal(node.asText().replace(",", "").trim()));
        } catch (NumberFormatException ignored) {
            // 숫자가 아닌 스칼라는 집계에서 제외한다.
        }
    }

    private int countScalars(JsonNode node) {
        if (node.isArray()) {
            int count = 0;
            for (JsonNode child : node) {
                count += countScalars(child);
            }
            return count;
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 필터
    // ------------------------------------------------------------------

    private boolean passesFilters(JsonNode item, List<ReshapingRule.FilterRule> filters, List<String> warnings) {
        for (ReshapingRule.FilterRule filter : filters) {
            if (!matches(item, filter, warnings)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(JsonNode item, ReshapingRule.FilterRule filter, List<String> warnings) {
        JsonNode actual = read(item, filter.path(), warnings);
        String op = filter.op().toUpperCase(Locale.ROOT);
        JsonNode expected = filter.value();
        return switch (op) {
            case "EXISTS" -> !isMissing(actual);
            case "EQ" -> sameValue(actual, expected);
            case "NE" -> !sameValue(actual, expected);
            case "IN" -> expected != null && expected.isArray()
                    && anyMatch(actual, expected);
            case "CONTAINS" -> containsValue(actual, expected);
            case "GT" -> compare(actual, expected) > 0;
            case "LT" -> compare(actual, expected) < 0;
            default -> false;
        };
    }

    private boolean anyMatch(JsonNode actual, JsonNode expected) {
        for (JsonNode candidate : expected) {
            if (sameValue(actual, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsValue(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null) {
            return false;
        }
        if (actual.isArray()) {
            for (JsonNode element : actual) {
                if (sameValue(element, expected)) {
                    return true;
                }
            }
            return false;
        }
        return actual.asText().contains(expected.asText());
    }

    private boolean sameValue(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null) {
            return false;
        }
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        if (actual.isArray()) {
            for (JsonNode element : actual) {
                if (sameValue(element, expected)) {
                    return true;
                }
            }
            return false;
        }
        return actual.asText().equals(expected.asText());
    }

    private int compare(JsonNode actual, JsonNode expected) {
        if (isMissing(actual) || expected == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return new BigDecimal(actual.asText()).compareTo(new BigDecimal(expected.asText()));
        } catch (NumberFormatException e) {
            return actual.asText().compareTo(expected.asText());
        }
    }

    // ------------------------------------------------------------------
    // JSON 유틸
    // ------------------------------------------------------------------

    private JsonNode read(JsonNode scope, String path, List<String> warnings) {
        if (scope == null || isMissing(scope)) {
            return null;
        }
        if (path == null || path.isBlank() || "$".equals(path)) {
            return scope;
        }
        try {
            Object value = JsonPath.using(jsonPathConfig).parse(scope.toString()).read(path);
            return toNode(value);
        } catch (InvalidPathException e) {
            throw new ReshapingException("JSONPath 문법이 올바르지 않습니다: " + path + " (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            warnings.add("경로 평가 실패: " + path + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private JsonNode toNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return mapper.readTree(trimmed);
                } catch (Exception ignored) {
                    return mapper.getNodeFactory().textNode(text);
                }
            }
            return mapper.getNodeFactory().textNode(text);
        }
        return mapper.valueToTree(value);
    }

    private List<JsonNode> asList(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (isMissing(node)) {
            return items;
        }
        if (node.isArray()) {
            node.forEach(items::add);
            return items;
        }
        items.add(node);
        return items;
    }

    private boolean isMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
