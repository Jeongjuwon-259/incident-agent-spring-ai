package com.samsung.gsre.agent.gateway.guard;

import com.samsung.gsre.agent.common.constant.SqlConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
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
 * <h2>상단 (INPUT) — 검증 기준정보</h2>
 * <ul>
 *   <li>SELECT/WITH 단일 문장만 허용, 주석·다중 문장 금지</li>
 *   <li>메타데이터에 등록된 테이블만 허용 (CTE 이름은 테이블이 아니므로 제외)</li>
 *   <li>묵시적 조인(FROM a, b) 금지 — 두 번째 테이블이 허용 목록 검사를 우회하지 못하게 한다</li>
 *   <li>모든 조회에 LIMIT 강제 (최대 max-rows)</li>
 *   <li>사용자 마스터는 정책(MASK/DENY/ALLOW)에 따라 제한, SELECT * 금지</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 검증</h2>
 * <ol>
 *   <li>문장 정규화(끝 세미콜론 제거) 및 금지 구문 검사</li>
 *   <li>테이블 추출 → 허용 목록 대조 (CTE 이름 제외)</li>
 *   <li>LIMIT 보정 및 PII 마스킹 필요 여부 판단</li>
 * </ol>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * {@link GuardVerdict} — 실행 가능 여부, 정규화 SQL, 위반 사유, 재시도 가능 여부.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlGuard {

    // ==================================================================
    // 상단 (INPUT) — 검증 기준정보
    // ==================================================================

    private static final Pattern ALLOWED_START = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("\\blimit\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CTE_REF = Pattern.compile(
            "(?is)\\bwith\\s+(?:recursive\\s+)?([a-z_][a-z0-9_$]*)\\s+as\\s*\\("
                    + "|,\\s*([a-z_][a-z0-9_$]*)\\s+as\\s*\\(");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(" + SqlConstants.DESTRUCTIVE_KEYWORDS + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(" + String.join("|", SqlConstants.SYSTEM_SCHEMAS) + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_STAR = Pattern.compile("select\\s+\\*", Pattern.CASE_INSENSITIVE);
    private static final List<String> FROM_END_KEYWORDS = SqlConstants.FROM_END_KEYWORDS;

    private final SchemaCatalog catalog;
    private final AgentProperties props;

    // ==================================================================
    // 중단 (PROCESS) — 검증
    // ==================================================================

    public GuardVerdict validate(String rawSql) {
        if (ValidationUtils.isBlank(rawSql)) {
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
        if (hasImplicitJoin(sql)) {
            violations.add("FROM 절에 콤마로 테이블을 나열할 수 없습니다. JOIN 을 명시하세요.");
            repairable = false;
        }

        Set<String> cteNames = cteNames(sql);
        Set<String> tables = new LinkedHashSet<>();
        Matcher tableMatcher = TABLE_REF.matcher(sql);
        while (tableMatcher.find()) {
            String table = SchemaCatalog.normalize(tableMatcher.group(1));
            if (cteNames.contains(table)) {
                continue;
            }
            tables.add(table);
            if (!catalog.isAllowed(table)) {
                violations.add("허용되지 않은 테이블입니다: " + table);
                repairable = false;
            }
        }

        boolean selectStar = SELECT_STAR.matcher(sql).find();
        String userMasterMode = props.getGuard().getUserMasterMode().toUpperCase(Locale.ROOT);
        boolean touchesUserMaster = tables.contains(SqlConstants.USER_MASTER_TABLE);
        if (touchesUserMaster && SqlConstants.USER_MASTER_MODE_DENY.equals(userMasterMode)) {
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

    // ==================================================================
    // 하단 (OUTPUT) — 판정 유틸 · LIMIT 보정
    // ==================================================================

    /** WITH 절의 CTE 이름. 이 이름은 테이블이 아니므로 허용 목록 대조에서 뺀다. */
    private Set<String> cteNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CTE_REF.matcher(sql);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (name != null) {
                names.add(SchemaCatalog.normalize(name));
            }
        }
        return names;
    }

    /**
     * FROM 절의 최상위(괄호 밖) 콤마를 찾는다. {@code FROM a, b} 는 b 가 허용 목록 검사를
     * 우회할 수 있으므로 금지하고, JOIN 을 명시하게 한다. 함수 인자({@code EXTRACT(... FROM ...)})나
     * 서브쿼리 내부 콤마는 깊이 계산으로 걸러낸다.
     */
    private boolean hasImplicitJoin(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int index = 0;
        while ((index = lower.indexOf("from", index)) >= 0) {
            boolean leftBoundary = index == 0 || !isWordChar(lower.charAt(index - 1));
            boolean rightBoundary = index + 4 >= lower.length() || !isWordChar(lower.charAt(index + 4));
            if (!leftBoundary || !rightBoundary) {
                index += 4;
                continue;
            }
            char quote = 0;
            int depth = 0;
            for (int i = index + 4; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                    continue;
                }
                if (c == '(') {
                    depth++;
                    continue;
                }
                if (c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                    continue;
                }
                if (depth > 0) {
                    continue;
                }
                if (c == ',') {
                    return true;
                }
                if (startsKeyword(lower, i)) {
                    break;
                }
            }
            index += 4;
        }
        return false;
    }

    private boolean startsKeyword(String lowerSql, int at) {
        for (String keyword : FROM_END_KEYWORDS) {
            int end = at + keyword.length();
            if (lowerSql.startsWith(keyword, at)
                    && (at == 0 || !isWordChar(lowerSql.charAt(at - 1)))
                    && (end >= lowerSql.length() || !isWordChar(lowerSql.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

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
