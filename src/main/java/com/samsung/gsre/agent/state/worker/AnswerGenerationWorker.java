package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.trace.TraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 6단계 — 결과 확인 + 최종 답변 생성.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>결과 충분성 판단과 답변 생성을 <b>LLM 1회 호출</b>로 통합한다 (지연·비용 절감).</li>
 *   <li>답변은 조회 결과에 근거한 사실만 담는다.</li>
 *   <li>부족하면 next_purposes 를 돌려주고, 흐름은 2단계로 되돌아가 추가 조회를 한다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>계획·실행 결과·대화 맥락을 LLM 에 넘긴다.</li>
 *   <li>sufficient / answer / missing_facts / next_purposes 를 컨텍스트에 저장한다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AnswerGenerationWorker {

    // ==================================================================
    // 상단 (INPUT) — 의존성
    // ==================================================================

    private final LlmGateway llmGateway;
    private final TraceRecorder traceRecorder;

    // ==================================================================
    // 중단 (PROCESS) — 답변 생성
    // ==================================================================

    public StageOutcome answer(PipelineContext ctx) {
        long started = System.nanoTime();
        AnswerOutcome outcome = llmGateway.composeAnswer(ctx.getQuestion(),
                ctx.getPlan() == null ? java.util.List.of() : ctx.getPlan().tasks(),
                ctx.getLookups(), ctx.getHistory());
        ctx.setAnswer(outcome);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = "답변 생성 (sufficient=" + outcome.sufficient()
                + ", 조회 " + ctx.getLookups().size() + "건)";
        String detail = "answer=" + outcome.answer() + "\nmissing=" + outcome.missingFacts()
                + "\nnext=" + outcome.nextPurposes();
        traceRecorder.record(ctx, Stage.ANSWER_GENERATION, summary, detail, elapsed);
        return new StageOutcome(Stage.ANSWER_GENERATION, summary, detail);
    }
}
