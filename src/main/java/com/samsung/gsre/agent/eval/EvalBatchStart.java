package com.samsung.gsre.agent.eval;

/** 평가 실행 접수 응답 (202 Accepted). */
public record EvalBatchStart(String batchId, String status, int totalCount) {
}
