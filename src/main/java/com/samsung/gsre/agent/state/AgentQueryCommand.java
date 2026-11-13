package com.samsung.gsre.agent.state;

/** 사용자 질의 입력 (Web/메신저 공통). */
public record AgentQueryCommand(String userId, String channel, String sessionId, String question) {
}
