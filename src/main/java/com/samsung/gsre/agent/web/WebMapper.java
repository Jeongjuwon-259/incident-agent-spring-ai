package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.eval.EvalBatch;
import com.samsung.gsre.agent.eval.EvalCaseResult;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.rag.VerifiedSqlEntry;
import com.samsung.gsre.agent.state.PipelineResult;
import com.samsung.gsre.agent.state.TraceStep;
import com.samsung.gsre.agent.web.dto.CacheEntryView;
import com.samsung.gsre.agent.web.dto.EvalBatchView;
import com.samsung.gsre.agent.web.dto.EvalResultView;
import com.samsung.gsre.agent.web.dto.LookupView;
import com.samsung.gsre.agent.web.dto.QueryResponse;
import com.samsung.gsre.agent.web.dto.TraceView;
import java.util.List;
import java.util.Map;

/** 도메인 결과 → 웹 응답 변환. Controller 가 비즈니스 흐름에만 집중하도록 분리한다. */
public final class WebMapper {

    private static final int PREVIEW_ROWS = 5;

    private WebMapper() {
    }

    public static QueryResponse toResponse(PipelineResult result) {
        return new QueryResponse(
                result.runId(),
                result.sessionId(),
                result.status() == null ? null : result.status().name(),
                result.answer(),
                result.cacheHit(),
                result.plan() == null ? null : result.plan().intent().name(),
                result.llmCalls(),
                result.durationMs(),
                result.errorMessage(),
                result.lookups().stream().map(WebMapper::toLookup).toList(),
                result.trace().stream().map(WebMapper::toTrace).toList());
    }

    private static LookupView toLookup(LookupResult lookup) {
        List<Map<String, Object>> rows = lookup.result() == null ? List.of() : lookup.result().rows();
        return new LookupView(
                lookup.taskId(),
                lookup.purpose(),
                lookup.sql(),
                lookup.parameters(),
                lookup.origin() == null ? null : lookup.origin().name(),
                rows.size(),
                rows.size() > PREVIEW_ROWS ? rows.subList(0, PREVIEW_ROWS) : rows,
                lookup.repairNotes(),
                lookup.failed(),
                lookup.errorMessage());
    }

    private static TraceView toTrace(TraceStep step) {
        return new TraceView(step.seq(), step.stage().name(), step.stage().label(),
                step.summary(), step.latencyMs());
    }

    public static CacheEntryView toCacheView(VerifiedSqlEntry entry) {
        return new CacheEntryView(entry.id(), entry.question(), entry.sql(), entry.tables(),
                entry.parameters(), entry.verifiedBy(), entry.notes(), entry.useCount(), entry.createdAt());
    }

    public static EvalBatchView toBatchView(EvalBatch batch) {
        return new EvalBatchView(batch.batchId(), batch.status(), batch.requestedBy(),
                batch.totalCount(), batch.completedCount(), batch.passedCount(), batch.passRate(),
                batch.startedAt(), batch.finishedAt(), batch.note());
    }

    public static EvalResultView toResultView(EvalCaseResult r) {
        return new EvalResultView(r.batchId(), r.tcId(), r.agentRunId(), r.actualSql(), r.finalAnswer(),
                r.questionSnapshot(), r.expectedSqlSnapshot(), r.expectedAnswerSnapshot(),
                r.exPass(), r.exReason(), r.aaPass(), r.aaReason(), r.fnPass(), r.fnReason(),
                r.execPass(), r.execReason(), r.overallPass(), r.overallReason(),
                r.durationMs(), r.createdAt());
    }
}
