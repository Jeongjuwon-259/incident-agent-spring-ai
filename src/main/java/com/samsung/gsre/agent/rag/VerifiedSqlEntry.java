package com.samsung.gsre.agent.rag;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 검증 SQL 캐시의 한 건. */
public record VerifiedSqlEntry(
        long id,
        String question,
        String sql,
        List<String> tables,
        Map<String, String> parameters,
        String verifiedBy,
        String notes,
        float[] embedding,
        String tokens,
        int useCount,
        Instant createdAt) {
}
