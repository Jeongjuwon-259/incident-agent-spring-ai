package com.samsung.gsre.agent.web.dto;

import java.time.Instant;

/** 평가 배치 상태/통과율. */
public record EvalBatchView(
        String batchId,
        String status,
        String requestedBy,
        int totalCount,
        int completedCount,
        int passedCount,
        double passRate,
        Instant startedAt,
        Instant finishedAt,
        String note) {
}
