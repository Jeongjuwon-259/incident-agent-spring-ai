package com.samsung.gsre.agent.model;

/** LLM-as-a-Judge 판정 결과. */
public record JudgeVerdict(boolean pass, String reason) {
    public static JudgeVerdict pass(String reason) {
        return new JudgeVerdict(true, reason);
    }

    public static JudgeVerdict fail(String reason) {
        return new JudgeVerdict(false, reason);
    }
}
