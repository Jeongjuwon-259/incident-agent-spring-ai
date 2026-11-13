package com.samsung.gsre.agent.nl2sql;

import java.util.List;

/** NL2SQL 메타데이터 딕셔너리의 테이블 한 건. */
public record TableMetadata(
        String name,
        String type,
        String description,
        List<String> keyColumns,
        List<ColumnMetadata> columns,
        List<JoinHint> joins,
        String notes) {

    public TableMetadata {
        keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
        columns = columns == null ? List.of() : List.copyOf(columns);
        joins = joins == null ? List.of() : List.copyOf(joins);
    }
}
