package com.samsung.gsre.agent.rag;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.gateway.GuardVerdict;
import com.samsung.gsre.agent.gateway.SqlGuard;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.rag.repository.VerifiedSqlRepository;
import com.samsung.gsre.agent.rag.similarity.SemanticSimilarity;
import com.samsung.gsre.agent.rag.similarity.SimilarityScore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 검증된 질의·SQL 시맨틱 캐시 (RAG).
 *
 * <h2>기준정보 (상단)</h2>
 * <ul>
 *   <li>캐시에는 <b>사람이 검증한 SQL만</b> 등록한다. 파이프라인은 자동 등록하지 않는다.</li>
 *   <li>등록 SQL 은 SqlGuard 로 한 번 더 검증한다 (허용 테이블·SELECT 전용).</li>
 *   <li>후보 채택 최소 유사도: {@code agent.rag.similarity-threshold}</li>
 *   <li>임베딩 불가 시 어휘 유사도로 폴백 (auto)</li>
 * </ul>
 *
 * <h2>가공 흐름 (중단)</h2>
 * <ol>
 *   <li>질의 임베딩을 1회 계산한다.</li>
 *   <li>검증 SQL 전체와 유사도를 계산해 상위 K건을 후보로 만든다.</li>
 *   <li>재사용 여부는 이 후보를 3단계(재사용 판단)가 판단한다. 유사도만으로 확정하지 않는다.</li>
 * </ol>
 *
 * <h2>결과 (하단)</h2>
 * {@link CacheCandidate} 목록 — id, 유사도, SQL, 테이블, 파라미터 기본값.
 */
@Slf4j
@Service
public class VerifiedSqlCacheService {

    private final VerifiedSqlRepository repository;
    private final SemanticSimilarity similarity;
    private final SqlGuard sqlGuard;
    private final AgentProperties props;

    public VerifiedSqlCacheService(VerifiedSqlRepository repository,
                                   SemanticSimilarity similarity,
                                   SqlGuard sqlGuard,
                                   AgentProperties props) {
        this.repository = repository;
        this.similarity = similarity;
        this.sqlGuard = sqlGuard;
        this.props = props;
    }

    public List<CacheCandidate> search(String question, int topK) {
        float[] queryEmbedding = props.getRag().isEmbeddingEnabled() ? similarity.embed(question) : null;
        double threshold = props.getRag().getSimilarityThreshold();
        return repository.findAll().stream()
                .map(entry -> {
                    SimilarityScore score = similarity.score(question, queryEmbedding,
                            entry.question(), entry.embedding());
                    return new CacheCandidate(entry.id(), entry.question(), entry.sql(),
                            score.score(), score.scoreType(), entry.tables(), entry.verifiedBy(),
                            entry.parameters(), entry.notes());
                })
                .filter(candidate -> candidate.score() >= threshold)
                .sorted(Comparator.comparingDouble(CacheCandidate::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * 검증 SQL 등록. {@code verifiedBy} 가 비어 있으면 거부한다.
     * (요구사항: SQL 은 검증 없이 RAG 에 자동 등록하지 않는다)
     */
    public CacheCandidate register(String question, String sql, Map<String, String> parameters,
                                   String verifiedBy, String notes) {
        if (!StringUtils.hasText(verifiedBy)) {
            throw new IllegalArgumentException("검증자(verifiedBy)가 필요합니다. 검증되지 않은 SQL 은 등록할 수 없습니다.");
        }
        GuardVerdict verdict = sqlGuard.validate(sql);
        if (!verdict.valid()) {
            throw new IllegalArgumentException("검증 SQL 이 안전 규칙을 통과하지 못했습니다: " + verdict.violations());
        }
        float[] embedding = props.getRag().isEmbeddingEnabled() ? similarity.embed(question) : null;
        String embeddingJson = similarity.toJson(embedding);
        long id = repository.insert(new VerifiedSqlEntry(0L, question, verdict.sql(), verdict.tables(),
                parameters == null ? Map.of() : parameters, verifiedBy, notes, embedding, null, 0,
                java.time.Instant.now()));
        if (id > 0 && embeddingJson != null) {
            repository.updateEmbedding(id, embeddingJson);
        }
        log.info("[rag] 검증 SQL 등록 id={} verifiedBy={} question={}", id, verifiedBy, question);
        return new CacheCandidate(id, question, verdict.sql(), 1.0,
                embedding != null ? "embedding" : "lexical", verdict.tables(), verifiedBy,
                parameters == null ? Map.of() : parameters, notes);
    }

    public void markUsed(long cacheId) {
        repository.incrementUse(cacheId);
    }

    public List<VerifiedSqlEntry> entries() {
        return repository.findAll();
    }

    /** 시작 시 임베딩이 없는 검증 SQL 을 채워 넣는다. 실패해도 기동은 계속한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpEmbeddings() {
        if (!props.getRag().isEmbeddingEnabled()) {
            return;
        }
        List<VerifiedSqlEntry> entries = repository.findAll();
        int filled = 0;
        for (VerifiedSqlEntry entry : entries) {
            if (entry.embedding() != null) {
                continue;
            }
            float[] vector = similarity.embed(entry.question());
            if (vector == null) {
                log.info("[rag] 임베딩 모델을 사용할 수 없어 어휘 유사도 모드로 동작합니다.");
                return;
            }
            repository.updateEmbedding(entry.id(), similarity.toJson(vector));
            filled++;
        }
        if (filled > 0) {
            log.info("[rag] 검증 SQL 임베딩 {}건 생성 완료", filled);
        }
    }
}
