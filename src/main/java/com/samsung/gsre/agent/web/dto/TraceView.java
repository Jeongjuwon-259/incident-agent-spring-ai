package com.samsung.gsre.agent.web.dto;

/** 단계별 실행 이력. */
public record TraceView(int seq, String stage, String stageLabel, String summary, long latencyMs) {
}
