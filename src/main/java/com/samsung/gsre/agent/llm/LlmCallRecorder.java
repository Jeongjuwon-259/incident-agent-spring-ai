package com.samsung.gsre.agent.llm;

/** LLM 호출 이력 저장소. 구현체는 감사 테이블(agent_llm_call)에 적재한다. */
@FunctionalInterface
public interface LlmCallRecorder {

    void record(LlmCallRecord record);

    LlmCallRecorder NO_OP = record -> { };
}
