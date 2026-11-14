package com.samsung.gsre.agent.state.worker;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.rag.VerifiedSqlCacheService;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.Stage;
import com.samsung.gsre.agent.state.StageOutcome;
import com.samsung.gsre.agent.state.trace.TraceRecorder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 2단계 — 검증 SQL RAG 검색.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>후보는 최대 {@code agent.rag.top-k} 건, 최소 유사도 {@code agent.rag.similarity-threshold} 이상.</li>
 *   <li>유사도는 임베딩(가능 시) 또는 어휘 유사도로 계산한다.</li>
 *   <li>이 단계는 후보만 모은다. 재사용 확정은 3단계가 한다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>실행 큐에서 이번 조회 작업을 꺼낸다.</li>
 *   <li>질의 + 작업 목적 + 조건으로 유사 검증 SQL 을 검색한다.</li>
 *   <li>후보를 컨텍스트에 담고 trace 에 남긴다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class RagCacheSearchWorker {

    private final VerifiedSqlCacheService cacheService;
    private final AgentProperties props;
    private final TraceRecorder traceRecorder;

    public StageOutcome search(PipelineContext ctx) {
        long started = System.nanoTime();
        LookupTask task = ctx.pollTask();
        ctx.setCurrentTask(task);

        String searchText = ctx.getQuestion() + " " + task.purpose() + " " + task.filters().values();
        List<CacheCandidate> candidates = cacheService.search(searchText, props.getRag().getTopK());
        ctx.setCandidates(candidates);

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String summary = "후보 " + candidates.size() + "건 (task=" + task.id() + ")";
        StringBuilder detail = new StringBuilder();
        candidates.forEach(c -> detail.append("id=").append(c.id()).append(" score=")
                .append(String.format("%.3f", c.score())).append("(").append(c.scoreType()).append(") ")
                .append(c.question()).append('\n'));
        traceRecorder.record(ctx, Stage.RAG_CACHE_SEARCH, summary, detail.toString(), elapsed);
        return new StageOutcome(Stage.RAG_CACHE_SEARCH, summary, detail.toString());
    }
}
