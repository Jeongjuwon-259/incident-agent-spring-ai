package com.samsung.gsre.agent.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** 검증 SQL 등록 요청. 검증자(verifiedBy) 없이는 등록할 수 없다. */
public record CacheEntryRequest(
        @NotBlank(message = "질의(question)는 필수입니다.") String question,
        @NotBlank(message = "SQL(sql)은 필수입니다.") String sql,
        Map<String, String> parameters,
        @NotBlank(message = "검증자(verifiedBy)는 필수입니다.") String verifiedBy,
        String notes) {
}
