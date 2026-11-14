package com.samsung.gsre.agent.nl2sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.nl2sql.metadata.ColumnMetadata;
import com.samsung.gsre.agent.nl2sql.metadata.JoinHint;
import com.samsung.gsre.agent.nl2sql.metadata.TableMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 8대 조회 대상의 메타데이터 딕셔너리.
 *
 * <p>테이블·컬럼·JOIN 관계·비즈니스 규칙을 한 곳에서 관리하고,
 * (1) NL2SQL 프롬프트, (2) SQL 안전 검증의 허용 테이블 목록, (3) PII 마스킹 대상으로 함께 사용한다.
 * 메타데이터를 코드가 아니라 리소스(JSON)로 둔 덕분에 스키마 변경 시 재배포 없이 교체할 수 있다.</p>
 */
@Slf4j
@Component
public class SchemaCatalog {

    // ==================================================================
    // 상단 (INPUT) — 기준정보(리소스 경로·메타데이터 사전)
    // ==================================================================

    private static final String RESOURCE = "nl2sql/schema-catalog.json";

    private final Map<String, TableMetadata> tables = new LinkedHashMap<>();
    private final List<String> businessRules = new ArrayList<>();

    public SchemaCatalog() {
        load();
    }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode rules = root.get("business_rules");
            if (rules != null && rules.isArray()) {
                rules.forEach(r -> businessRules.add(r.asText()));
            }
            JsonNode tableNodes = root.get("tables");
            if (tableNodes != null && tableNodes.isArray()) {
                for (JsonNode t : tableNodes) {
                    TableMetadata meta = toTable(t);
                    tables.put(normalize(meta.name()), meta);
                }
            }
            log.info("[nl2sql] 메타데이터 로드 완료: {} tables, {} rules", tables.size(), businessRules.size());
        } catch (IOException e) {
            throw new IllegalStateException("NL2SQL 메타데이터를 읽을 수 없습니다: " + RESOURCE, e);
        }
    }

    private TableMetadata toTable(JsonNode t) {
        List<ColumnMetadata> columns = new ArrayList<>();
        JsonNode cols = t.get("columns");
        if (cols != null) {
            for (JsonNode c : cols) {
                columns.add(new ColumnMetadata(
                        c.path("name").asText(),
                        c.path("type").asText(),
                        c.path("description").asText(""),
                        c.path("pii").asBoolean(false)));
            }
        }
        List<JoinHint> joins = new ArrayList<>();
        JsonNode joinNodes = t.get("joins");
        if (joinNodes != null) {
            for (JsonNode j : joinNodes) {
                joins.add(new JoinHint(j.path("to_table").asText(), j.path("on").asText(),
                        j.path("description").asText("")));
            }
        }
        List<String> keys = new ArrayList<>();
        JsonNode keyNodes = t.get("key_columns");
        if (keyNodes != null) {
            keyNodes.forEach(k -> keys.add(k.asText()));
        }
        return new TableMetadata(
                t.path("name").asText(),
                t.path("type").asText("TABLE"),
                t.path("description").asText(""),
                keys,
                columns,
                joins,
                t.path("notes").asText(""));
    }

    // ==================================================================
    // 중단 (PROCESS) — 메타데이터 조회
    // ==================================================================

    /** 대소문자/스키마 접두어를 제거한 정규화 이름. */
    public static String normalize(String tableName) {
        if (ValidationUtils.isBlank(tableName)) {
            return "";
        }
        String name = tableName.trim().replace("\"", "").replace("`", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.toLowerCase();
    }

    public boolean isAllowed(String tableName) {
        return tables.containsKey(normalize(tableName));
    }

    public Optional<TableMetadata> find(String tableName) {
        return Optional.ofNullable(tables.get(normalize(tableName)));
    }

    public Set<String> allowedTables() {
        return new LinkedHashSet<>(tables.keySet());
    }

    public List<String> businessRules() {
        return List.copyOf(businessRules);
    }

    public List<ColumnMetadata> columnsOf(String tableName) {
        return find(tableName).map(TableMetadata::columns).orElse(List.of());
    }

    public List<String> columnNames(String tableName) {
        return columnsOf(tableName).stream().map(ColumnMetadata::name).toList();
    }

    public boolean isPii(String tableName, String columnName) {
        return columnsOf(tableName).stream()
                .anyMatch(c -> c.name().equalsIgnoreCase(columnName) && c.pii());
    }

    public List<String> piiColumns(String tableName) {
        return columnsOf(tableName).stream().filter(ColumnMetadata::pii).map(ColumnMetadata::name).toList();
    }

    // ==================================================================
    // 하단 (OUTPUT) — 프롬프트용 렌더링
    // ==================================================================

    /** 프롬프트에 넣을 압축 메타데이터 블록. */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (TableMetadata t : tables.values()) {
            sb.append("- ").append(t.name()).append(" (").append(t.type()).append(") ").append(t.description()).append('\n');
            sb.append("  key: ").append(String.join(", ", t.keyColumns())).append('\n');
            sb.append("  columns: ");
            for (int i = 0; i < t.columns().size(); i++) {
                ColumnMetadata c = t.columns().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(c.name()).append(' ').append(c.type());
                if (StringUtils.hasText(c.description())) {
                    sb.append(" /* ").append(c.description()).append(" */");
                }
            }
            sb.append('\n');
            for (JoinHint j : t.joins()) {
                sb.append("  join -> ").append(j.toTable()).append(" ON ").append(j.on()).append('\n');
            }
            if (StringUtils.hasText(t.notes())) {
                sb.append("  note: ").append(t.notes()).append('\n');
            }
        }
        if (!businessRules.isEmpty()) {
            sb.append("\n[비즈니스 규칙]\n");
            for (String rule : businessRules) {
                sb.append("- ").append(rule).append('\n');
            }
        }
        return sb.toString();
    }
}
