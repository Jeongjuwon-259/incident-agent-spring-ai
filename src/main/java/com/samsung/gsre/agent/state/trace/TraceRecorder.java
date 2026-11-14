package com.samsung.gsre.agent.state.trace;

import com.samsung.gsre.agent.audit.repository.AuditRepository;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 단계별 실행 이력 기록기.
 *
 * <p>개발 디버깅 로그([stage] ...)와 감사 테이블(agent_run_step)을 동시에 남긴다.
 * 같은 runId 로 묶이므로 "어느 단계에서 무엇을 판단했는지" 를 한 흐름으로 추적할 수 있다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceRecorder {

    private final AuditRepository auditRepository;

    public void record(PipelineContext ctx, Stage stage, String summary, String detail, long latencyMs) {
        int seq = ctx.getTrace().size() + 1;
        TraceStep step = new TraceStep(seq, stage, summary, detail, latencyMs, java.time.Instant.now());
        ctx.getTrace().add(step);
        auditRepository.saveStep(ctx.getRunId(), step);
        log.info("[stage] runId={} #{}/{} {} | {} ({}ms)",
                ctx.getRunId(), seq, stage.order(), stage.name(), summary, latencyMs);
    }
}
