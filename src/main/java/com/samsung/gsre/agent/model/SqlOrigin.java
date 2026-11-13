package com.samsung.gsre.agent.model;

/** SQL 출처. 품질 분석에서 재사용 질의와 신규 생성 질의를 구분하기 위해 사용한다. */
public enum SqlOrigin {
    REUSED_CACHE,
    GENERATED,
    REPAIRED
}
