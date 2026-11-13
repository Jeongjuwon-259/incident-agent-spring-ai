package com.samsung.gsre.agent.llm;

import java.time.Instant;

/** LLM 호출 1회의 감사 기록. 실행 ID 로 Agent 실행 이력과 연결된다. */
public record LlmCallRecord(
        String runId,
        String purpose,
        String provider,
        String model,
        String prompt,
        String response,
        long latencyMs,
        int promptChars,
        int responseChars,
        int estimatedTokens,
        boolean success,
        String errorMessage,
        Instant createdAt) {
}
