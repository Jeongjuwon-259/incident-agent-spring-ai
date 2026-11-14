package com.samsung.gsre.agent.mcp.validation;

import com.samsung.gsre.agent.common.constant.SqlConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * INBOUND 원천 조회({@code source_query})가 읽기 전용 단일 SELECT 인지 검증하는 정책.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>SELECT 또는 WITH 로 시작하는 <b>단일 문장</b>만 허용한다.</li>
 *   <li>한 줄 주석, 블록 주석, {@code #} 주석과 세미콜론을 금지한다.</li>
 *   <li>변경·DDL·실행·파일 접근 키워드를 금지한다.</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 검증</h2>
 * <p>정규화(끝 세미콜론 제거) → 주석/다중문장 → 시작 키워드 → 금지 키워드 순으로 확인한다.</p>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>실행에 쓸 정규화 SQL. 위반이면 {@link IllegalArgumentException} (등록 400 / 실행 FAILED).</p>
 *
 * <p>등록 시점({@link McpApiRegistrationValidator})과 실행 시점({@code McpSourceQueryReader})에서
 * 같은 규칙을 두 번 적용한다. 테이블은 API 밖에서 수정될 수 있기 때문이다.</p>
 */
@Component
public class SourceQueryValidator {

    private static final Pattern SELECT_ONLY = Pattern.compile("(?is)^\\s*(select|with)\\b.*");
    private static final Pattern COMMENT = Pattern.compile("(?s)(--|/\\*|\\*/)|(^|\\s)#");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(" + SqlConstants.DESTRUCTIVE_KEYWORDS + ")\\b");

    // ==================================================================
    // 중단 (PROCESS) — 검증
    // ==================================================================

    public String validate(McpApiMaster api) {
        ValidationUtils.requirePresent(api, "MCP API 정의가 없습니다.");
        return validate(api.apiId(), api.sourceQuery());
    }

    /** @return 정규화된 실행 SQL (끝 세미콜론 제거) */
    public String validate(String apiId, String rawSql) {
        ValidationUtils.requireText(rawSql, "source_query", "source_query 가 비어 있습니다: " + apiId);
        String sql = rawSql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("source_query 는 단일 SELECT 문만 허용합니다: " + apiId);
        }
        if (COMMENT.matcher(sql).find()) {
            throw new IllegalArgumentException("source_query 에 주석을 쓸 수 없습니다: " + apiId);
        }
        if (!SELECT_ONLY.matcher(sql).matches()) {
            throw new IllegalArgumentException("source_query 는 SELECT/WITH 로 시작해야 합니다: " + apiId);
        }
        var matcher = FORBIDDEN.matcher(sql);
        if (matcher.find()) {
            throw new IllegalArgumentException(
                    "source_query 에 변경/실행 구문 키워드가 있습니다(" + matcher.group().toLowerCase(Locale.ROOT)
                            + "): " + apiId);
        }
        return sql;
    }
}
