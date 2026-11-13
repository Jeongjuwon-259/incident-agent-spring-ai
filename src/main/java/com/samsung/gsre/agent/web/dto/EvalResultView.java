package com.samsung.gsre.agent.web.dto;

import java.time.Instant;

/** 테스트 케이스별 평가 결과. */
public record EvalResultView(
        String batchId,
        String tcId,
        String agentRunId,
        String actualSql,
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
