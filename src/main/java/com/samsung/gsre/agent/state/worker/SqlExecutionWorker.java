package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.gateway.SelfRepairEngine;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.TraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 5단계 — SQL 검증·실행·Self-Repair.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>실행은 SqlGuard → DbExecutor → SelfRepairEngine 순서로 단일 관문을 통과한다.</li>
 *   <li>같은 목적의 조회가 이미 성공했다면 다시 실행하지 않고 기존 결과를 쓴다.</li>
 *   <li>한 질의의 최대 실행 수는 {@code agent.pipeline.max-executions}.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>중복 조회인지 확인한다.</li>
 *   <li>실행하고 결과/재시도 이력을 컨텍스트에 누적한다.</li>
 *   <li>실패해도 파이프라인을 멈추지 않고 다음 작업 또는 답변 생성으로 넘긴다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SqlExecutionWorker {

    private final SelfRepairEngine selfRepairEngine;
    private final AgentProperties props;
    private final TraceRecorder traceRecorder;

    public StageOutcome execute(PipelineContext ctx) {
        long started = System.nanoTime();
        LookupTask task = ctx.getCurrentTask();

        LookupResult existing = ctx.getLookups().stream()
                .filter(r -> !r.failed() && r.purpose().equals(task.purpose()))
                .findFirst().orElse(null);
        if (existing != null) {
            ctx.setLastLookup(existing);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            String summary = "이미 조회한 결과 재사용 (task=" + task.id() + ")";
            traceRecorder.record(ctx, Stage.SQL_VALIDATE_EXECUTE, summary, "purpose=" + task.purpose(), elapsed);
            return new StageOutcome(Stage.SQL_VALIDATE_EXECUTE, summary, "");
        }

        LookupResult result = selfRepairEngine.execute(ctx.getQuestion(), task, ctx.getPreparedSql(),
                ctx.getCandidates());
        ctx.addLookup(result);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = result.failed()
                ? "실행 실패: " + shorten(result.errorMessage())
                : "실행 성공 " + result.result().rowCount() + "건 (" + result.origin() + ")";
        String detail = "sql=" + result.sql() + "\nparams=" + result.parameters()
                + "\nrows=" + result.result().rowCount() + "\nrepair=" + result.repairNotes()
                + (result.failed() ? "\nerror=" + result.errorMessage() : "");
        traceRecorder.record(ctx, Stage.SQL_VALIDATE_EXECUTE, summary, detail, elapsed);
        return new StageOutcome(Stage.SQL_VALIDATE_EXECUTE, summary, detail);
    }

    private String shorten(String message) {
        if (message == null) {
            return "알 수 없는 오류";
        }
        return message.length() <= 200 ? message : message.substring(0, 200) + "...";
    }
}
