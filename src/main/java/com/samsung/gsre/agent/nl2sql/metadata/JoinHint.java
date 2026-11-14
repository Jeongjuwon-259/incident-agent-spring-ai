package com.samsung.gsre.agent.nl2sql.metadata;

/** 테이블 간 조인 힌트. NL2SQL 프롬프트에 그대로 제공된다. */
public record JoinHint(String toTable, String on, String description) {
}
