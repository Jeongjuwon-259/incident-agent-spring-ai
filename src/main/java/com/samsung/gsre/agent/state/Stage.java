package com.samsung.gsre.agent.state;

/**
 * 6단계 명시적 상태머신. LangGraph 없이 이 순서를 코드로 고정한다.
 *
 * <p>순서: 질의이해 → RAG캐시검색 → 재사용판단 → SQL준비 → 검증/실행/Self-Repair → 답변생성.
 * 2~5단계는 조회 작업(태스크)마다 반복되고, 6단계에서 결과가 부족하면 2단계로 되돌아간다.</p>
 */
public enum Stage {

    QUERY_UNDERSTANDING(1, "질의 이해 및 조회 계획"),
    RAG_CACHE_SEARCH(2, "검증 SQL RAG 검색"),
    REUSE_DECISION(3, "SQL 재사용 판단"),
    SQL_PREPARATION(4, "SQL 준비 (재사용 적용 / 신규 생성)"),
    SQL_VALIDATE_EXECUTE(5, "SQL 검증·실행·Self-Repair"),
    ANSWER_GENERATION(6, "결과 확인 및 답변 생성");

    private final int order;
    private final String label;

    Stage(int order, String label) {
        this.order = order;
        this.label = label;
    }

    public int order() {
        return order;
    }

    public String label() {
        return label;
    }
}
