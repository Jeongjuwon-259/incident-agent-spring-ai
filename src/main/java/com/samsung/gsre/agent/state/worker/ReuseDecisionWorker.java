package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.rag.VerifiedSqlCacheService;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.trace.TraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 3단계 — SQL 재사용 판단.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>유사도만으로 재사용을 확정하지 않는다. 조회 대상·조건·파라미터 적합성을 확인한다.</li>
 *   <li>후보가 없으면 LLM 을 호출하지 않고 즉시 "재사용 안 함" 으로 끝낸다 (불필요한 호출 제거).</li>
 *   <li>재사용이 확정되면 캐시 사용 횟수를 올려 품질 분석 지표로 남긴다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>2단계 후보와 현재 조회 작업을 LLM 에 넘겨 재사용 여부를 판단한다.</li>
 *   <li>재사용이면 사용할 후보 ID 와 바인딩 파라미터를 컨텍스트에 저장한다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ReuseDecisionWorker {

    private final LlmGateway llmGateway;
    private final VerifiedSqlCacheService cacheService;
    private final TraceRecorder traceRecorder;

    public StageOutcome decide(PipelineContext ctx) {
        long started = System.nanoTime();
        if (ctx.getCandidates().isEmpty()) {
            ReuseDecision decision = ReuseDecision.reject("검증 SQL 후보가 없습니다.");
            ctx.setReuseDecision(decision);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            traceRecorder.record(ctx, Stage.REUSE_DECISION, "재사용 안 함(후보 없음)", "", elapsed);
            return new StageOutcome(Stage.REUSE_DECISION, "재사용 안 함(후보 없음)", "");
        }

        ReuseDecision decision = llmGateway.judgeReuse(ctx.getQuestion(), ctx.getPlan(),
                ctx.getCurrentTask(), ctx.getCandidates());
        ctx.setReuseDecision(decision);
        if (decision.reuse() && decision.candidateId() != null) {
            ctx.setCacheHitCount(ctx.getCacheHitCount() + 1);
            cacheService.markUsed(decision.candidateId());
        }

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = decision.reuse()
                ? "검증 SQL 재사용 (cacheId=" + decision.candidateId() + ")"
                : "신규 생성 필요";
        String detail = decision.reason() + " / params=" + decision.parameters();
        traceRecorder.record(ctx, Stage.REUSE_DECISION, summary, detail, elapsed);
        return new StageOutcome(Stage.REUSE_DECISION, summary, detail);
    }
}
