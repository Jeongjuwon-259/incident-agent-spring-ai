package com.samsung.gsre.agent.model;

import java.util.Map;

/** 3단계(재사용 판단) 산출물. */
public record ReuseDecision(
        boolean reuse,
        Long candidateId,
        String reason,
        Map<String, Object> parameters) {

    public ReuseDecision {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static ReuseDecision reject(String reason) {
        return new ReuseDecision(false, null, reason, Map.of());
    }

    public static ReuseDecision accept(long candidateId, String reason, Map<String, Object> params) {
        return new ReuseDecision(true, candidateId, reason, params);
    }
}
