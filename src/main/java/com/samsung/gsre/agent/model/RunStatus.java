package com.samsung.gsre.agent.model;

/** 실행 상태. PARTIAL 은 일부 조회 실패에도 답변을 만든 경우다. */
public enum RunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED
}
