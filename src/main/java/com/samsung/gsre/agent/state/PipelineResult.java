package com.samsung.gsre.agent.state;

import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.RunStatus;
import java.util.List;

/**
 * 질의 처리 최종 결과 (Controller 응답 스펙).
 */
public record PipelineResult(
        String runId,
        String sessionId,
        RunStatus status,
        String answer,
        boolean cacheHit,
        QueryPlan plan,
        List<LookupResult> lookups,
        List<TraceStep> trace,
        int llmCalls,
        long durationMs,
        String errorMessage) {

    public static PipelineResult of(PipelineContext ctx, int llmCalls) {
        return new PipelineResult(
                ctx.getRunId(),
                ctx.getSessionId(),
                ctx.getStatus(),
                ctx.getAnswer() == null ? null : ctx.getAnswer().answer(),
                ctx.cacheHit(),
                ctx.getPlan(),
                List.copyOf(ctx.getLookups()),
                List.copyOf(ctx.getTrace()),
                llmCalls,
                ctx.getDurationMs(),
                ctx.getErrorMessage());
    }
}
