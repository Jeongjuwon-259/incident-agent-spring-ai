package com.samsung.gsre.agent.eval;

import com.samsung.gsre.agent.model.SqlExecutionResult;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Execution Accuracy(EX) 비교기.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>SQL 문장이 달라도 <b>실행 결과가 같으면 Pass</b>.</li>
 *   <li>행 순서는 무시한다 (정렬 비교).</li>
 *   <li>컬럼명이 같으면 컬럼명 기준, 다르면 값 집합 기준으로 비교한다 (별칭 차이 허용).</li>
 *   <li>숫자는 BigDecimal 로 비교해 1 과 1.0 을 같게 본다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>결과셋을 정규화된 행 서명 목록으로 바꾼다.</li>
 *   <li>정렬 후 목록이 완전히 같으면 Pass.</li>
 * </ol>
 */
@Component
public class ResultSetComparator {

    public boolean matches(SqlExecutionResult expected, SqlExecutionResult actual) {
        if (expected == null || actual == null) {
            return false;
        }
        List<String> expectedRows = signatures(expected);
        List<String> actualRows = signatures(actual);
        return expectedRows.equals(actualRows);
    }

    public boolean matchesAny(SqlExecutionResult expected, List<SqlExecutionResult> candidates) {
        if (candidates == null) {
            return false;
        }
        for (SqlExecutionResult candidate : candidates) {
            if (matches(expected, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 결과 한 건을 행 서명 목록으로 정규화한다. */
    public List<String> signatures(SqlExecutionResult result) {
        if (result == null) {
            return List.of();
        }
        Set<String> expectedColumns = new LinkedHashSet<>(normalizeColumns(result.columns()));
        List<String> signatures = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            if (row.size() == expectedColumns.size() && row.keySet().stream()
                    .map(c -> c.toLowerCase(java.util.Locale.ROOT)).allMatch(expectedColumns::contains)) {
                List<String> values = new ArrayList<>();
                for (String column : expectedColumns) {
                    values.add(normalizeValue(row.get(column)));
                }
                signatures.add(String.join("|", values));
            } else {
                List<String> values = new ArrayList<>();
                row.values().forEach(v -> values.add(normalizeValue(v)));
                Collections.sort(values);
                signatures.add(String.join("|", values));
            }
        }
        Collections.sort(signatures);
        return signatures;
    }

    private List<String> normalizeColumns(List<String> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).toList();
    }

    private String normalizeValue(Object value) {
        if (value == null) {
            return "\u0000NULL";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return String.valueOf(value).strip();
    }
}
