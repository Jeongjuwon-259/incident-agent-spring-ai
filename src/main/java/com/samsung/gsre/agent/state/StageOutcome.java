package com.samsung.gsre.agent.state;

/** 워커 1회 실행 결과. summary 는 사람이 읽는 한 줄, detail 은 판단 근거(JSON/텍스트). */
public record StageOutcome(Stage stage, String summary, String detail) {
}
