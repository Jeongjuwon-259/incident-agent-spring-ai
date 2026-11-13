package com.samsung.gsre.agent.rag;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 어휘 기반 유사도 (임베딩 모델이 없거나 실패했을 때의 폴백).
 *
 * <p>한국어는 조사가 붙어 형태가 달라지므로 공백 토큰과 함께 글자 bigram 을 벡터에 넣는다.
 * "서비스맵" 과 "서비스맵포털" 처럼 부분 겹침이 있는 질의도 후보로 잡힌다.</p>
 */
public final class LexicalSimilarity {

    private LexicalSimilarity() {
    }

    public static double score(String query, String candidate) {
        if (query == null || candidate == null) {
            return 0.0;
        }
        Map<String, Integer> a = vector(query);
        Map<String, Integer> b = vector(candidate);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            Integer other = b.get(entry.getKey());
            if (other != null) {
                dot += entry.getValue() * other;
            }
        }
        double normA = Math.sqrt(a.values().stream().mapToDouble(v -> (double) v * v).sum());
        double normB = Math.sqrt(b.values().stream().mapToDouble(v -> (double) v * v).sum());
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (normA * normB);
    }

    static Map<String, Integer> vector(String text) {
        Map<String, Integer> vector = new HashMap<>();
        String normalized = text.toLowerCase(Locale.KOREAN).replaceAll("[\\p{Punct}·“”‘’]", " ").replaceAll("\\s+", " ").strip();
        for (String token : normalized.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            vector.merge(token, 1, Integer::sum);
            if (token.length() >= 2) {
                for (int i = 0; i < token.length() - 1; i++) {
                    vector.merge(token.substring(i, i + 2), 1, Integer::sum);
                }
            }
        }
        return vector;
    }
}
