package com.samsung.gsre.agent.model;

import java.util.List;
import java.util.Map;

/** 4단계(SQL 준비) 산출물. 실행 직전의 SQL·바인딩 파라미터. */
public record PreparedSql(
        String sql,
        Map<String, Object> parameters,
        List<String> tables,
        SqlOrigin origin,
        Long cacheId,
        String notes) {

    public PreparedSql {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
