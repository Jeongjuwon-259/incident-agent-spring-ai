package com.samsung.gsre.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 테스트 케이스 등록/수정 요청. */
public record EvalCaseRequest(
        @NotBlank(message = "질의(question)는 필수입니다.") String question,
        @NotBlank(message = "예상 SQL(expectedSql)은 필수입니다.") String expectedSql,
        String expectedAnswer,
        String category,
        Boolean enabled) {
}
