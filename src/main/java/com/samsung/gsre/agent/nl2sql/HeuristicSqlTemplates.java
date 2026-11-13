package com.samsung.gsre.agent.nl2sql;

import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.SqlOrigin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 SQL 생성기 (오프라인 모드 · 파싱 실패 폴백 · Self-Repair 보조).
 *
 * <p>의도(Intent)별 표준 조회 템플릿을 사용한다. 실제 운영에서는 로컬 LLM 이 NL2SQL 을 생성하고,
 * 이 클래스는 모델이 없거나 응답이 깨졌을 때 파이프라인이 멈추지 않게 하는 안전망 역할을 한다.</p>
 */
@Component
@RequiredArgsConstructor
public class HeuristicSqlTemplates {

    private static final Pattern FROM_TABLE = Pattern.compile("(?is)\\bfrom\\s+([a-z_][a-z0-9_]*)\\s*([a-z][a-z0-9_]*)?");

    private final SchemaCatalog catalog;

    public PreparedSql build(String question, QueryPlan plan, LookupTask task) {
        Map<String, Object> params = new LinkedHashMap<>();
        String appId = task.filters().get("appId");
        String appName = task.filters().get("appName");
        String deptName = task.filters().get("deptName");
        String aliasName = task.filters().get("aliasName");

        String sql = switch (task.intent()) {
            case CONTACT -> """
                    SELECT a.app_id, a.app_name, u.user_name, d.dept_name, u.email, u.phone
                    FROM svcm_tb_app_master a
                    JOIN svcm_tb_app_user_map m ON m.app_id = a.app_id AND m.role_code = 'OWNER'
                    JOIN svcm_tb_user_info_master u ON u.user_id = m.user_id
                    LEFT JOIN svcm_tb_dept_info_master d ON d.dept_id = u.dept_id
                    WHERE %s
                    ORDER BY u.user_name
                    LIMIT 20
                    """.formatted(appFilter("a", appId, appName, params));
            case USER_ACCESS, COUNT -> (appId != null && !appId.isBlank())
                    ? countSql(appId, params)
                    : """
                    SELECT a.app_id, a.app_name, COUNT(*) AS user_count
                    FROM svcm_tb_app_user_map m
                    JOIN svcm_tb_app_master a ON a.app_id = m.app_id
                    WHERE %s
                    GROUP BY a.app_id, a.app_name
                    ORDER BY user_count DESC
                    LIMIT 20
                    """.formatted(appFilter("a", appId, appName, params));
            case DEPT -> deptName != null
                    ? deptSql(deptName, params)
                    : """
                    SELECT d.dept_id, d.dept_name, d.dept_head_name, d.org_code
                    FROM svcm_tb_dept_info_master d
                    JOIN svcm_tb_app_master a ON a.dept_id = d.dept_id
                    WHERE %s
                    LIMIT 20
                    """.formatted(appFilter("a", appId, appName, params));
            case SERVICE_MAP -> """
                    SELECT a.app_id, a.app_name, s.service_id, s.service_name, s.domain_name
                    FROM svcm_tb_app_master a
                    LEFT JOIN svcm_tb_service_map_master s ON s.service_id = a.service_id
                    WHERE %s
                    LIMIT 20
                    """.formatted(appFilter("a", appId, appName, params));
            case JIRA_WORKLOG -> """
                    SELECT v.issue_key, v.app_id, v.app_name, v.assignee_name, v.assignee_dept,
                           v.issue_type, v.status, v.summary, v.worklog_hours, v.worklog_date
                    FROM svcm_vw_jira_dri_worklog v
                    WHERE %s
                    ORDER BY v.worklog_date DESC
                    LIMIT 10
                    """.formatted(appFilter("v", appId, appName, params));
            case ALIAS -> aliasName != null
                    ? aliasSqlByAlias(aliasName, params)
                    : """
                    SELECT al.alias_id, al.app_id, a.app_name, al.alias_name, al.alias_type
                    FROM svcm_tb_app_alias al
                    JOIN svcm_tb_app_master a ON a.app_id = al.app_id
                    WHERE %s
                    LIMIT 20
                    """.formatted(appFilter("al", appId, appName, params));
            case APP_INFO, GENERAL -> """
                    SELECT a.app_id, a.app_name, a.app_type, a.status, a.criticality,
                           d.dept_name, s.service_name
                    FROM svcm_tb_app_master a
                    LEFT JOIN svcm_tb_dept_info_master d ON d.dept_id = a.dept_id
                    LEFT JOIN svcm_tb_service_map_master s ON s.service_id = a.service_id
                    WHERE %s
                    LIMIT 20
                    """.formatted(appFilter("a", appId, appName, params));
        };

        return new PreparedSql(clean(sql), params, task.targetTables(), SqlOrigin.GENERATED, null,
                "규칙 기반 표준 템플릿(intent=" + task.intent() + ")");
    }

    /**
     * 자주 발생하는 두 가지 오류만 코드로 고친다: SELECT * 와 LIMIT 누락.
     * 그 외에는 null 을 돌려주어 상위 루프가 재시도를 멈추게 한다.
     */
    public PreparedSql repair(PreparedSql failed, String errorMessage) {
        String sql = failed.sql();
        String lower = sql.toLowerCase(Locale.ROOT);
        String repaired = null;

        if (lower.matches("(?s).*select\\s+\\*.*")) {
            repaired = expandSelectStar(sql);
        }
        if (repaired == null && !lower.contains("limit") && errorMessage != null
                && lower.contains("select")) {
            repaired = sql.strip() + "\nLIMIT 50";
        }
        if (repaired == null || repaired.equals(sql)) {
            return null;
        }
        return new PreparedSql(repaired, failed.parameters(), failed.tables(), SqlOrigin.REPAIRED,
                failed.cacheId(), "규칙 기반 자동 수정(SELECT * 확장 / LIMIT 보정)");
    }

    private String expandSelectStar(String sql) {
        Matcher matcher = FROM_TABLE.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String table = matcher.group(1);
        String alias = matcher.group(2) == null ? table : matcher.group(2);
        List<String> columns = catalog.columnNames(table);
        if (columns.isEmpty()) {
            return null;
        }
        String list = String.join(", ", columns.stream().map(c -> alias + "." + c).toList());
        return sql.replaceFirst("(?is)select\\s+\\*", "SELECT " + list);
    }

    private String countSql(String appId, Map<String, Object> params) {
        params.put("appId", appId);
        return """
                SELECT COUNT(*) AS user_count
                FROM svcm_tb_app_user_map m
                WHERE m.app_id = :appId
                LIMIT 10
                """;
    }

    private String deptSql(String deptName, Map<String, Object> params) {
        params.put("deptName", "%" + deptName + "%");
        return """
                SELECT d.dept_id, d.dept_name, d.dept_head_name, d.org_code
                FROM svcm_tb_dept_info_master d
                WHERE d.dept_name LIKE :deptName
                ORDER BY d.dept_name
                LIMIT 20
                """;
    }

    private String aliasSqlByAlias(String aliasName, Map<String, Object> params) {
        params.put("aliasName", "%" + aliasName + "%");
        return """
                SELECT al.alias_id, al.app_id, a.app_name, al.alias_name, al.alias_type
                FROM svcm_tb_app_alias al
                JOIN svcm_tb_app_master a ON a.app_id = al.app_id
                WHERE al.alias_name LIKE :aliasName
                ORDER BY al.alias_type
                LIMIT 20
                """;
    }

    private String appFilter(String alias, String appId, String appName, Map<String, Object> params) {
        if (appId != null && !appId.isBlank()) {
            params.put("appId", appId);
            return alias + ".app_id = :appId";
        }
        if (appName != null && !appName.isBlank()) {
            params.put("appName", "%" + appName + "%");
            return alias + ".app_name LIKE :appName";
        }
        return "1 = 1";
    }

    private String clean(String sql) {
        List<String> lines = new ArrayList<>();
        for (String line : sql.strip().split("\n")) {
            lines.add(line.stripTrailing());
        }
        return String.join("\n", lines).strip();
    }
}
