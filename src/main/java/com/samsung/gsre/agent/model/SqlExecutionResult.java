package com.samsung.gsre.agent.model;

import java.util.List;
import java.util.Map;

/** DB 실행 결과. 결과셋은 컬럼 순서를 유지한 LinkedHashMap 목록이다. */
public record SqlExecutionResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        long elapsedMs) {

    public SqlExecutionResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static SqlExecutionResult empty() {
        return new SqlExecutionResult(List.of(), List.of(), 0, 0L);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
