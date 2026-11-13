package com.samsung.gsre.agent.web.dto;

import java.util.List;
import java.util.Map;

/** 실행 이력 상세 (agent_run + 단계 + LLM 호출). */
public record RunDetailView(
        Map<String, Object> run,
        List<Map<String, Object>> steps,
        List<Map<String, Object>> llmCalls) {
}
