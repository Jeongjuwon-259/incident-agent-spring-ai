package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 선언적 Data Reshaping 룰. 테이블의 {@code request_reshaping_rule} / {@code response_reshaping_rule}
 * CLOB 이 그대로 이 구조로 파싱된다.
 *
 * <pre>
 * { "version": "1.0",
 *   "mode": "COLLECTION",              // OBJECT | COLLECTION
 *   "rootPath": "$",                   // (선택) 실제 데이터 위치
 *   "itemPath": "$.items[*]",          // COLLECTION: 반복할 배열 경로
 *   "filter": [{"path": "$.status.phase", "op": "NE", "value": "Succeeded"}],
 *   "limit": 10,
 *   "dropNull": true,
 *   "fields": [
 *     {"target": "podName", "source": "$.metadata.name", "required": true},
 *     {"target": "restarts", "source": "$.status.containerStatuses[*].restartCount", "aggregate": "SUM", "default": 0}
 *   ],
 *   "envelope": {"type": "MCP_TOOL_CALL", "tool": "jira.worklog.sync", "argumentsKey": "entries"}
 * }
 * </pre>
 *
 * <p>룰 문법은 방향과 무관하다. INBOUND 는 DB 로우(JSON 배열)에, OUTBOUND 는 외부 응답 JSON 에 같은
 * 엔진을 적용한다. 이것이 "양방향" 의 실체다.</p>
 */
public record ReshapingRule(
        String version,
        Mode mode,
        String rootPath,
        String itemPath,
        List<FilterRule> filters,
        Integer limit,
        boolean dropNull,
        List<FieldRule> fields,
        Envelope envelope) {

    public enum Mode { OBJECT, COLLECTION }

    /** 값 변환. 룰에 문자열로 적히고, 파싱 시점에 검증된다. */
    public enum Transform { NONE, TRIM, UPPER, LOWER, TO_STRING, TO_NUMBER, TO_INT, TO_BOOLEAN }

    /** 여러 값을 하나로 접는 집계. (예: 컨테이너별 restartCount → Pod 총 재시작 수) */
    public enum Aggregate { SUM, COUNT, AVG, MIN, MAX }

    /** 항목 단위 필터. path 는 항목 기준 상대 JSONPath, value 는 배열도 허용. */
    public record FilterRule(String path, String op, JsonNode value) {
    }

    /**
     * @param target      결과 JSON 의 필드명
     * @param source      JSONPath (항목/루트 기준). constant 와 둘 중 하나만
     * @param constant    원천 없이 고정값을 넣을 때 (예: severity = "HIGH")
     * @param transform   값 변환
     * @param aggregate   source 가 여러 값을 반환할 때 접는 방법
     * @param defaultValue 원천이 없을 때 대체값
     * @param required    true 면 원천이 없을 때 실행을 실패시킨다
     */
    public record FieldRule(String target, String source, JsonNode constant, Transform transform,
                            Aggregate aggregate, JsonNode defaultValue, boolean required) {
    }

    /** 결과를 MCP tools/call 규격({tool, arguments})으로 감싼다. */
    public record Envelope(String type, String tool, String argumentsKey) {
        public boolean toolCall() {
            return "MCP_TOOL_CALL".equalsIgnoreCase(type);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ReshapingRule parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ReshapingException("Reshaping 룰이 비어 있습니다.");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ReshapingException("Reshaping 룰 JSON 을 해석하지 못했습니다: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ReshapingException("Reshaping 룰은 JSON 객체여야 합니다.");
        }

        Mode mode = parseEnum(Mode.class, root.path("mode").asText("OBJECT"), "mode");
        String rootPath = normalizePath(root.path("rootPath").asText("$"));
        String itemPath = normalizePath(root.path("itemPath").asText(mode == Mode.COLLECTION ? "$[*]" : ""));
        boolean dropNull = root.path("dropNull").asBoolean(false);
        Integer limit = root.hasNonNull("limit") ? root.get("limit").asInt() : null;

        List<FilterRule> filters = new ArrayList<>();
        for (JsonNode f : root.path("filter")) {
            String path = normalizePath(f.path("path").asText(""));
            if (path.isEmpty()) {
                throw new ReshapingException("filter.path 가 비어 있습니다.");
            }
            String op = f.path("op").asText("EQ").trim().toUpperCase(Locale.ROOT);
            if (!List.of("EQ", "NE", "IN", "CONTAINS", "EXISTS", "GT", "LT").contains(op)) {
                throw new ReshapingException("지원하지 않는 filter.op 입니다: " + op);
            }
            filters.add(new FilterRule(path, op, f.get("value")));
        }

        List<FieldRule> fields = new ArrayList<>();
        for (JsonNode f : root.path("fields")) {
            String target = f.path("target").asText("");
            if (target.isBlank()) {
                throw new ReshapingException("fields[].target 은 필수입니다.");
            }
            boolean hasSource = f.hasNonNull("source");
            boolean hasConstant = f.hasNonNull("constant");
            if (hasSource == hasConstant) {
                throw new ReshapingException(
                        "필드 '" + target + "' 는 source 또는 constant 중 정확히 하나가 필요합니다.");
            }
            String source = hasSource ? normalizePath(f.get("source").asText("")) : null;
            Transform transform = parseEnum(Transform.class, f.path("transform").asText("NONE"), "transform");
            Aggregate aggregate = f.hasNonNull("aggregate")
                    ? parseEnum(Aggregate.class, f.get("aggregate").asText(), "aggregate")
                    : null;
            fields.add(new FieldRule(target, source, f.get("constant"), transform, aggregate,
                    f.get("default"), f.path("required").asBoolean(false)));
        }
        if (fields.isEmpty()) {
            throw new ReshapingException("fields 가 비어 있습니다. 최소 1개 매핑이 필요합니다.");
        }

        Envelope envelope = null;
        JsonNode env = root.get("envelope");
        if (env != null && !env.isNull()) {
            String type = env.path("type").asText("MCP_TOOL_CALL");
            envelope = new Envelope(type, env.path("tool").asText(""), env.path("argumentsKey").asText(""));
        }

        return new ReshapingRule(root.path("version").asText("1.0"), mode, rootPath.isEmpty() ? "$" : rootPath,
                itemPath, filters, limit, dropNull, fields, envelope);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("$")) {
            return trimmed;
        }
        return "$." + trimmed;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ReshapingException(field + " 값이 올바르지 않습니다: " + raw);
        }
    }
}
