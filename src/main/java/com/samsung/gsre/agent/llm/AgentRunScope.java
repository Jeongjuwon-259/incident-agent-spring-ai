package com.samsung.gsre.agent.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 현재 스레드에서 실행 중인 Agent run 의 범위 정보.
 * LLM 호출 횟수·지연을 run 단위로 집계하고, 호출 이력에 runId 를 붙이는 데 사용한다.
 */
public final class AgentRunScope implements AutoCloseable {

    private static final ThreadLocal<AgentRunScope> CURRENT = new ThreadLocal<>();

    private final String runId;
    private final int maxLlmCalls;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicLong llmLatencyMs = new AtomicLong();

    private AgentRunScope(String runId, int maxLlmCalls) {
        this.runId = runId;
        this.maxLlmCalls = maxLlmCalls;
    }

    public static AgentRunScope open(String runId, int maxLlmCalls) {
        AgentRunScope scope = new AgentRunScope(runId, maxLlmCalls);
        CURRENT.set(scope);
        return scope;
    }

    public static AgentRunScope current() {
        return CURRENT.get();
    }

    public static String currentRunId() {
        AgentRunScope scope = CURRENT.get();
        return scope == null ? null : scope.runId;
    }

    public String runId() {
        return runId;
    }

    public int llmCalls() {
        return llmCalls.get();
    }

    public long llmLatencyMs() {
        return llmLatencyMs.get();
    }

    public int incrementLlmCalls() {
        return llmCalls.incrementAndGet();
    }

    public void addLatency(long ms) {
        llmLatencyMs.addAndGet(ms);
    }

    /** 호출 예산 초과 여부. 초과 시 파이프라인은 추가 LLM 호출 없이 종료한다. */
    public boolean budgetExceeded() {
        return maxLlmCalls > 0 && llmCalls.get() >= maxLlmCalls;
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
