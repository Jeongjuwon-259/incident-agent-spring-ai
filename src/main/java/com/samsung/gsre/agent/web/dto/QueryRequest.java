package com.samsung.gsre.agent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 사용자 질의 요청. */
public record QueryRequest(
        String userId,
        String channel,
        String sessionId,
        @NotBlank(message = "질의(question)는 필수입니다.")
        @Size(max = 2000, message = "질의는 2000자 이하여야 합니다.")
        String question) {
}
