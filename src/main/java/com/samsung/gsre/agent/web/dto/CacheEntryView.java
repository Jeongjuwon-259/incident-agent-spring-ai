package com.samsung.gsre.agent.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 검증 SQL 캐시 조회 스펙. */
public record CacheEntryView(
        long id,
        String question,
        String sql,
        List<String> tables,
        Map<String, String> parameters,
        String verifiedBy,
        String notes,
        int useCount,
        Instant createdAt) {
}
