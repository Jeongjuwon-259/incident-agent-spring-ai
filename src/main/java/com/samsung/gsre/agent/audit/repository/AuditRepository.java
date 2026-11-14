package com.samsung.gsre.agent.audit.repository;

import com.samsung.gsre.agent.llm.AgentRunScope;
import com.samsung.gsre.agent.llm.support.LlmCallRecord;
import com.samsung.gsre.agent.llm.support.LlmCallRecorder;
import com.samsung.gsre.agent.state.PipelineContext;
import com.samsung.gsre.agent.state.trace.TraceStep;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 감사 로그 저장소.
 *
 * <p>한 질의 = 하나의 runId 로 묶어 (1) 실행 요약(agent_run), (2) 단계 이력(agent_run_step),
 * (3) LLM 호출 이력(agent_llm_call)을 남긴다. 개발 디버깅 로그와 감사 로그가 같은 runId 로 연결된다.</p>
 */
@Repository
public class AuditRepository implements LlmCallRecorder {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void startRun(PipelineContext ctx) {
        jdbc.update("""
                INSERT INTO agent_run (run_id, user_id, channel, session_id, question, status,
                                       cache_hit, execution_count, llm_call_count, iteration_count,
                                       duration_ms, started_at)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, 0, 0, 0, 0, ?)
                """, ctx.getRunId(), ctx.getUserId(), ctx.getChannel(), ctx.getSessionId(),
                truncate(ctx.getQuestion(), 2000), ctx.getStatus().name(),
                java.sql.Timestamp.from(ctx.getStartedAt()));
    }

    public void finishRun(PipelineContext ctx, int llmCalls) {
        jdbc.update("""
                UPDATE agent_run
                   SET status = ?, answer = ?, cache_hit = ?, execution_count = ?, llm_call_count = ?,
                       iteration_count = ?, duration_ms = ?, error_message = ?, finished_at = ?
                 WHERE run_id = ?
                """, ctx.getStatus().name(),
                ctx.getAnswer() == null ? null : ctx.getAnswer().answer(),
                ctx.cacheHit(), ctx.getExecutionCount(), llmCalls, ctx.getExtraRounds() + 1,
                ctx.getDurationMs(), truncate(ctx.getErrorMessage(), 1000),
                java.sql.Timestamp.from(java.time.Instant.now()), ctx.getRunId());
    }

    public void saveStep(String runId, TraceStep step) {
        jdbc.update("""
                INSERT INTO agent_run_step (run_id, seq_no, stage, summary, detail, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, runId, step.seq(), step.stage().name(), truncate(step.summary(), 500),
                step.detail(), step.latencyMs());
    }

    @Override
    public void record(LlmCallRecord call) {
        jdbc.update("""
                INSERT INTO agent_llm_call (run_id, purpose, provider, model, prompt, response,
                                            latency_ms, prompt_chars, response_chars, estimated_tokens,
                                            success, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, call.runId() == null ? AgentRunScope.currentRunId() : call.runId(),
                call.purpose(), call.provider(), call.model(), call.prompt(), call.response(),
                call.latencyMs(), call.promptChars(), call.responseChars(), call.estimatedTokens(),
                call.success(), truncate(call.errorMessage(), 1000));
    }

    public List<Map<String, Object>> findRun(String runId) {
        return jdbc.queryForList("SELECT * FROM agent_run WHERE run_id = ?", runId);
    }

    public List<Map<String, Object>> findSteps(String runId) {
        return jdbc.queryForList("""
                SELECT seq_no, stage, summary, detail, latency_ms, created_at
                FROM agent_run_step WHERE run_id = ? ORDER BY seq_no
                """, runId);
    }

    public List<Map<String, Object>> findLlmCalls(String runId) {
        return jdbc.queryForList("""
                SELECT purpose, provider, model, latency_ms, prompt_chars, response_chars,
                       estimated_tokens, success, error_message, created_at
                FROM agent_llm_call WHERE run_id = ? ORDER BY id
                """, runId);
    }

    public List<Map<String, Object>> findRunsBySession(String sessionId, int limit) {
        return jdbc.queryForList("""
                SELECT run_id, question, status, cache_hit, execution_count, llm_call_count,
                       duration_ms, started_at, finished_at
                FROM agent_run WHERE session_id = ? ORDER BY started_at DESC LIMIT ?
                """, sessionId, limit);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
