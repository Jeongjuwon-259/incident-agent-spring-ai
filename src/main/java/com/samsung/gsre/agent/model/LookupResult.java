package com.samsung.gsre.agent.model;

import java.util.List;
import java.util.Map;

/** 5단계(검증·실행·Self-Repair) 산출물. 실행 1건의 최종 상태. */
public record LookupResult(
        String taskId,
        String purpose,
        String sql,
        Map<String, Object> parameters,
        SqlExecutionResult result,
        SqlOrigin origin,
        List<String> repairNotes,
        boolean failed,
        String errorMessage) {

    public LookupResult {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        repairNotes = repairNotes == null ? List.of() : List.copyOf(repairNotes);
    }

    public static LookupResult failed(String taskId, String purpose, String sql,
                                      Map<String, Object> params, SqlOrigin origin,
                                      List<String> repairNotes, String error) {
        return new LookupResult(taskId, purpose, sql, params, SqlExecutionResult.empty(),
                origin, repairNotes, true, error);
    }
}
