package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.SqlOrigin;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.trace.TraceRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 4단계 — SQL 준비.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>3단계에서 재사용이 확정되면 검증 SQL 을 그대로 쓰고 파라미터만 바꾼다.</li>
 *   <li>아니면 메타데이터 + 유사 SQL 을 참고해 신규 SQL 을 생성한다.</li>
 *   <li>파라미터 우선순위: 검증 SQL 기본값 → 재사용 판단이 제안한 값 → 질의에서 추출한 조건.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>재사용 여부를 확인한다.</li>
 *   <li>재사용이면 후보 SQL + 병합 파라미터로 PreparedSql 을 만든다.</li>
 *   <li>아니면 NL2SQL 게이트웨이로 생성하고 출처(GENERATED)를 표시한다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SqlCompileWorker {

    private final LlmGateway llmGateway;
    private final TraceRecorder traceRecorder;

    public StageOutcome compile(PipelineContext ctx) {
        long started = System.nanoTime();
        LookupTask task = ctx.getCurrentTask();
        ReuseDecision decision = ctx.getReuseDecision();
        PreparedSql prepared;

        CacheCandidate reused = decision != null && decision.reuse()
                ? ctx.getCandidates().stream().filter(c -> c.id() == decision.candidateId()).findFirst().orElse(null)
                : null;

        if (reused != null) {
            Map<String, Object> params = new LinkedHashMap<>(reused.defaultParams());
            params.putAll(decision.parameters());
            params.putAll(task.filters());
            prepared = new PreparedSql(reused.sql(), params, reused.tables(), SqlOrigin.REUSED_CACHE,
                    reused.id(), "검증 SQL 재사용");
        } else {
            prepared = llmGateway.generateSql(ctx.getQuestion(), ctx.getPlan(), task, ctx.getCandidates());
        }
        ctx.setPreparedSql(prepared);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = prepared.origin() == SqlOrigin.REUSED_CACHE
                ? "검증 SQL 적용 (cacheId=" + prepared.cacheId() + ")"
                : "신규 SQL 생성";
        String detail = prepared.sql() + "\nparams=" + prepared.parameters()
                + (prepared.notes() == null ? "" : "\nnotes=" + prepared.notes());
        traceRecorder.record(ctx, Stage.SQL_PREPARATION, summary, detail, elapsed);
        return new StageOutcome(Stage.SQL_PREPARATION, summary, detail);
    }
}
