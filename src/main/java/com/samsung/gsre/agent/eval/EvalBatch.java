package com.samsung.gsre.agent.eval;

import java.time.Instant;

/** 평가 실행 배치. 웹에서 실행할 때마다 새 배치가 만들어진다. */
public record EvalBatch(
        String batchId,
        String requestedBy,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int totalCount,
        int completedCount,
        int passedCount,
        double passRate,
        String note) {
}
