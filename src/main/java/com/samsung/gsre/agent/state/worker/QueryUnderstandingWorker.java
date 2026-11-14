package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.Intent;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.trace.TraceRecorder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 1단계 — 질의 이해 및 조회 계획.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>조회 작업은 최대 {@code agent.pipeline.max-tasks} 개까지만 계획한다.</li>
 *   <li>계획이 비면 GENERAL 작업 1개로 안전하게 대체한다.</li>
 *   <li>추가 조회(REPLAN)는 답변 생성기가 돌려준 next_purposes 로만 만든다 (LLM 재호출 없음).</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>LLM(또는 규칙)에 질의와 멀티턴 맥락을 넘겨 조회 계획을 받는다.</li>
 *   <li>작업 수를 상한으로 자른 뒤 실행 큐(pendingTasks)에 적재한다.</li>
 *   <li>판단 근거를 trace 에 남긴다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class QueryUnderstandingWorker {

    // ==================================================================
    // 상단 (INPUT) — 의존성
    // ==================================================================

    private final LlmGateway llmGateway;
    private final AgentProperties props;
    private final TraceRecorder traceRecorder;

    // ==================================================================
    // 중단 (PROCESS) — 질의 이해 → 조회 계획
    // ==================================================================

    public StageOutcome understand(PipelineContext ctx) {
        long started = System.nanoTime();
        QueryPlan plan = llmGateway.understand(ctx.getQuestion(), ctx.getHistory());
        List<LookupTask> tasks = new ArrayList<>(plan.tasks());
        if (tasks.isEmpty()) {
            tasks.add(defaultTask(plan));
        }
        tasks = tasks.stream().limit(Math.max(1, props.getPipeline().getMaxTasks())).toList();
        QueryPlan bounded = new QueryPlan(plan.normalizedQuestion(), plan.intent(), plan.appIds(),
                plan.keywords(), tasks, plan.multiStep(), plan.followUp(), plan.reasoning());

        ctx.setPlan(bounded);
        ctx.getPendingTasks().clear();
        tasks.forEach(ctx.getPendingTasks()::add);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = "의도=" + plan.intent() + ", 조회작업 " + tasks.size() + "개 계획";
        String detail = "plan=" + plan.reasoning() + " / tasks=" + tasks;
        traceRecorder.record(ctx, Stage.QUERY_UNDERSTANDING, summary, detail, elapsed);
        return new StageOutcome(Stage.QUERY_UNDERSTANDING, summary, detail);
    }

    /** 답변 생성기가 "더 필요하다"고 한 조회 목적을 새 작업으로 만든다. */
    public StageOutcome replan(PipelineContext ctx) {
        long started = System.nanoTime();
        List<String> purposes = ctx.getAnswer() == null ? List.of() : ctx.getAnswer().nextPurposes();
        Map<String, String> filters = new LinkedHashMap<>();
        if (ctx.getPlan() != null && !ctx.getPlan().appIds().isEmpty()) {
            filters.put("appId", ctx.getPlan().appIds().get(0));
        }
        int seq = ctx.getPlan() == null ? 0 : ctx.getPlan().tasks().size();
        List<LookupTask> newTasks = new ArrayList<>();
        for (int i = 0; i < purposes.size(); i++) {
            newTasks.add(new LookupTask("R" + (seq + i + 1), purposes.get(i), Intent.GENERAL,
                    List.of(), filters));
        }
        if (newTasks.isEmpty()) {
            return new StageOutcome(Stage.QUERY_UNDERSTANDING, "추가 조회 없음", "");
        }
        newTasks.forEach(ctx.getPendingTasks()::add);
        ctx.setExtraRounds(ctx.getExtraRounds() + 1);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = "추가 조회 " + newTasks.size() + "건 재계획 (" + ctx.getExtraRounds()
                + "/" + props.getPipeline().getMaxExtraRounds() + " 라운드)";
        traceRecorder.record(ctx, Stage.QUERY_UNDERSTANDING, summary, newTasks.toString(), elapsed);
        return new StageOutcome(Stage.QUERY_UNDERSTANDING, summary, newTasks.toString());
    }

    private LookupTask defaultTask(QueryPlan plan) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (!plan.appIds().isEmpty()) {
            filters.put("appId", plan.appIds().get(0));
        }
        return new LookupTask("T1", "질의 조회", plan.intent() == null ? Intent.GENERAL : plan.intent(),
                List.of(), filters);
    }
}
