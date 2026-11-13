package com.samsung.gsre.agent.state;

import java.time.Instant;

/** 단계별 실행 이력 1건. agent_run_step 테이블과 1:1 로 저장된다. */
public record TraceStep(
        int seq,
        Stage stage,
        String summary,
        String detail,
        long latencyMs,
        Instant createdAt) {
}
