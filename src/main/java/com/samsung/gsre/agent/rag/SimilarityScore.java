package com.samsung.gsre.agent.rag;

/** 유사도 계산 결과. scoreType 은 embedding / lexical 중 무엇으로 계산했는지 나타낸다. */
public record SimilarityScore(double score, String scoreType) {
}
