package com.samsung.gsre.agent.web.dto;

import java.util.List;

/** 질의 처리 응답. 실행 추적(runId)과 단계별 SQL/결과 요약을 함께 돌려준다. */
public record QueryResponse(
        String runId,
        String sessionId,
        String status,
        String answer,
        boolean cacheHit,
        String planIntent,
        int llmCalls,
        long durationMs,
        String errorMessage,
        List<LookupView> lookups,
        List<TraceView> trace) {
}
