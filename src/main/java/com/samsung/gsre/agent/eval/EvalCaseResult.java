package com.samsung.gsre.agent.eval;

import java.time.Instant;

/** 테스트 케이스 1건의 평가 결과 (Batch ID + TC ID). */
public record EvalCaseResult(
        String batchId,
        String tcId,
        String agentRunId,
        String actualSql,
        String actualResult,
        String finalAnswer,
        String questionSnapshot,
        String expectedSqlSnapshot,
        String expectedAnswerSnapshot,
        boolean exPass,
        String exReason,
        boolean aaPass,
        String aaReason,
        boolean fnPass,
        String fnReason,
        boolean execPass,
        String execReason,
        boolean overallPass,
        String overallReason,
        long durationMs,
        Instant createdAt) {
}
