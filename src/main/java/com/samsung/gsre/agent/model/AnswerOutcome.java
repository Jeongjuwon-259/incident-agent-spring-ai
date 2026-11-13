package com.samsung.gsre.agent.model;

import java.util.List;

/**
 * 6단계(결과 확인 + 답변 생성)를 한 번의 LLM 호출로 처리한 결과.
 *
 * @param sufficient   누적 조회 결과가 질문을 충족하는지
 * @param answer       사용자에게 보여줄 최종 답변
 * @param missingFacts 부족한 정보 (재계획 근거)
 * @param nextPurposes 추가로 필요한 조회 목적
 */
public record AnswerOutcome(
        boolean sufficient,
        String answer,
        List<String> missingFacts,
        List<String> nextPurposes) {

    public AnswerOutcome {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        nextPurposes = nextPurposes == null ? List.of() : List.copyOf(nextPurposes);
    }
}
