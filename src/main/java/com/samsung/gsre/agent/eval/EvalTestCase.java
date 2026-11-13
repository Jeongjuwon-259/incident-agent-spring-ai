package com.samsung.gsre.agent.eval;

import java.time.Instant;

/** 품질 평가 테스트 케이스 (질문 1건 = 평가 단위). */
public record EvalTestCase(
        String tcId,
        String question,
        String expectedSql,
        String expectedAnswer,
        String category,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
