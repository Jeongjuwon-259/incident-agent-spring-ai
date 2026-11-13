package com.samsung.gsre.agent.web.dto;

import java.util.List;
import java.util.Map;

/** 실행 1건의 요약 (SQL, 파라미터, 행 수, 결과 미리보기). */
public record LookupView(
        String taskId,
        String purpose,
        String sql,
        Map<String, Object> parameters,
        String origin,
        int rowCount,
        List<Map<String, Object>> preview,
        List<String> repairNotes,
        boolean failed,
        String errorMessage) {
}
