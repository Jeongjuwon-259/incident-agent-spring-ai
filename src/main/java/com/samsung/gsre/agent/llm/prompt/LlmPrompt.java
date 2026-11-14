package com.samsung.gsre.agent.llm.prompt;

/** LLM 호출 1회의 입력. system/user 프롬프트와 샘플링 파라미터. */
public record LlmPrompt(String system, String user, double temperature, int maxTokens) {
}
