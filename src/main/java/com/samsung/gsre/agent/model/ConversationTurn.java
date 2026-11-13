package com.samsung.gsre.agent.model;

import java.time.Instant;

/** 멀티턴 세션의 한 턴. */
public record ConversationTurn(String role, String content, String runId, Instant createdAt) {
}
