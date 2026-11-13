package com.samsung.gsre.agent.gateway;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.nl2sql.SchemaCatalog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SQL 안전 검증기 (일반 코드로 처리하는 영역).
 *
 * <h2>기준정보 (상단)</h2>
 * <ul>
 *   <li>SELECT/WITH 단일 문장만 허용, 주석·다중 문장 금지</li>
 *   <li>메타데이터에 등록된 테이블만 허용 (이외 테이블은 재시도 불가 위반)</li>
 *   <li>모든 조회에 LIMIT 강제 (최대 max-rows)</li>
 *   <li>사용자 마스터는 정책(MASK/DENY/ALLOW)에 따라 제한, SELECT * 금지</li>
 * </ul>
 *
 * <h2>가공 흐름 (중단)</h2>
 * <ol>
 *   <li>문장 정규화 및 금지 구문 검사</li>
 *   <li>테이블 추출 → 허용 목록 대조</li>
 *   <li>LIMIT 보정 및 PII 마스킹 필요 여부 판단</li>
 * </ol>
 *
 * <h2>결과 (하단)</h2>
 * {@link GuardVerdict} — 실행 가능 여부, 정규화 SQL, 위반 사유, 재시도 가능 여부.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlGuard {

    // ------------------------------------------------------------------
    // 상단: 검증 기준정보
    // ------------------------------------------------------------------

    private static final Pattern ALLOWED_START = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("\\blimit\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|replace)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(information_schema|sys|pg_catalog|mysql|performance_schema)\\b", Pattern.CASE_INSENSITIVE);
    private static final String USER_MASTER = "svcm_tb_user_info_master";

    private final SchemaCatalog catalog;
    private final AgentProperties props;

    // ------------------------------------------------------------------
    // 중단: 검증 로직
    // ------------------------------------------------------------------

    public GuardVerdict validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardVerdict.reject(List.of("SQL 이 비어 있습니다."), false);
        }
        String sql = rawSql.strip().replaceAll(";\\s*$", "");
        List<String> violations = new ArrayList<>();
        boolean repairable = true;

        if (sql.contains(";")) {
            violations.add("다중 문장은 허용되지 않습니다.");
            repairable = false;
        }
        if (sql.contains("--") || sql.contains("/*")) {
            violations.add("SQL 주석은 허용되지 않습니다.");
            repairable = false;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!ALLOWED_START.matcher(sql).find()) {
            violations.add("SELECT 문만 실행할 수 있습니다.");
            repairable = false;
        }
        if (FORBIDDEN.matcher(sql).find()) {
            violations.add("변경성 구문(INSERT/UPDATE/DELETE/DDL)이 포함되어 있습니다.");
            repairable = false;
        }
        if (SYSTEM_SCHEMA.matcher(lower).find()) {
            violations.add("시스템 스키마 접근은 허용되지 않습니다.");
            repairable = false;
        }

        Set<String> tables = new LinkedHashSet<>();
        Matcher tableMatcher = TABLE_REF.matcher(sql);
        while (tableMatcher.find()) {
            tables.add(SchemaCatalog.normalize(tableMatcher.group(1)));
        }
        for (String table : tables) {
            if (!catalog.isAllowed(table)) {
                violations.add("허용되지 않은 테이블입니다: " + table);
                repairable = false;
            }
        }

        boolean selectStar = Pattern.compile("select\\s+\\*", Pattern.CASE_INSENSITIVE).matcher(sql).find();
        String userMasterMode = props.getGuard().getUserMasterMode().toUpperCase(Locale.ROOT);
        boolean touchesUserMaster = tables.contains(USER_MASTER);
        if (touchesUserMaster && "DENY".equals(userMasterMode)) {
            violations.add("사용자 정보 마스터 직접 조회는 차단됩니다(TABLE_NOT_ALLOWED).");
            repairable = false;
        }
        if (touchesUserMaster && selectStar) {
            violations.add("사용자 정보 마스터는 SELECT * 조회가 금지됩니다. 필요한 컬럼만 명시해야 합니다.");
            repairable = true;
        }

        if (!violations.isEmpty()) {
            return GuardVerdict.reject(violations, repairable);
        }

        String limited = applyLimit(sql);
        boolean maskPii = props.getGuard().isMaskPii() && tables.stream()
                .anyMatch(t -> !catalog.piiColumns(t).isEmpty());
        return GuardVerdict.ok(limited, List.copyOf(tables), maskPii);
    }

    // ------------------------------------------------------------------
    // 하단: LIMIT 보정 (실행 SQL 스펙)
    // ------------------------------------------------------------------

    private String applyLimit(String sql) {
        int maxRows = props.getGuard().getMaxRows();
        Matcher matcher = LIMIT_CLAUSE.matcher(sql);
        if (matcher.find()) {
            int limit = Integer.parseInt(matcher.group(1));
            if (limit <= 0 || limit > maxRows) {
                return matcher.replaceFirst("LIMIT " + maxRows);
            }
            return sql;
        }
        return sql + "\nLIMIT " + maxRows;
    }
}
