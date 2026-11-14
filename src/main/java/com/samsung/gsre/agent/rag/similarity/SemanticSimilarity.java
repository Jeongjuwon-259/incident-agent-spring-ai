package com.samsung.gsre.agent.rag.similarity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.llm.support.JsonSupport;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 시맨틱 캐시 유사도 계산기.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>임베딩 모델(로컬 LLM 의 /v1/embeddings)을 사용할 수 있으면 코사인 유사도</li>
 *   <li>모델이 없거나 호출이 실패하면 어휘 유사도로 자동 폴백 (auto 모드)</li>
 *   <li>임베딩 실패는 1회만 시도하고 그 뒤로는 어휘 유사도를 유지한다 (질의 지연 보호)</li>
 * </ul>
 */
@Component
public class SemanticSimilarity {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectMapper mapper = JsonSupport.mapper();
    private volatile boolean embeddingAvailable;

    public SemanticSimilarity(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingAvailable = embeddingModelProvider.getIfAvailable() != null;
    }

    /** 질의 임베딩. 사용할 수 없으면 null (어휘 유사도로 동작). */
    public float[] embed(String text) {
        if (!embeddingAvailable) {
            return null;
        }
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            embeddingAvailable = false;
            return null;
        }
        try {
            return model.embed(text);
        } catch (RuntimeException e) {
            embeddingAvailable = false;
            return null;
        }
    }

    public SimilarityScore score(String query, float[] queryEmbedding, String candidateQuestion,
                                 float[] candidateEmbedding) {
        if (embeddingAvailable && queryEmbedding != null && candidateEmbedding != null) {
            double cosine = cosine(queryEmbedding, candidateEmbedding);
            if (cosine > 0) {
                return new SimilarityScore(cosine, "embedding");
            }
        }
        return new SimilarityScore(LexicalSimilarity.score(query, candidateQuestion), "lexical");
    }

    public boolean isEmbeddingAvailable() {
        return embeddingAvailable;
    }

    public String toJson(float[] vector) {
        if (vector == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    public float[] parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
