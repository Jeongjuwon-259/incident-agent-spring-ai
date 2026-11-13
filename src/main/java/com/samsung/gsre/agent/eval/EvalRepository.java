package com.samsung.gsre.agent.eval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 품질 평가 저장소.
 *
 * <p>평가 결과는 덮어쓰지 않고 누적한다. 같은 TC 를 여러 번 평가하면 배치마다 별도 행이 쌓여
 * "품질 개선 전후 비교" 가 가능하다. 평가 당시의 질문·예상 SQL·예상 답변은 스냅샷으로 함께 보존한다.</p>
 */
@Repository
public class EvalRepository {

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 테스트 케이스 ----------------

    public List<EvalTestCase> findCases(boolean enabledOnly) {
        String sql = """
                SELECT tc_id, question, expected_sql, expected_answer, category, enabled, created_at, updated_at
                FROM eval_test_case
                """ + (enabledOnly ? " WHERE enabled = TRUE " : " ") + " ORDER BY tc_id";
        return jdbc.query(sql, (rs, rowNum) -> mapCase(rs));
    }

    public Optional<EvalTestCase> findCase(String tcId) {
        return jdbc.query("""
                SELECT tc_id, question, expected_sql, expected_answer, category, enabled, created_at, updated_at
                FROM eval_test_case WHERE tc_id = ?
                """, (rs, rowNum) -> mapCase(rs), tcId).stream().findFirst();
    }

    public void insertCase(EvalTestCase tc) {
        jdbc.update("""
                INSERT INTO eval_test_case (tc_id, question, expected_sql, expected_answer, category, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tc.tcId(), tc.question(), tc.expectedSql(), tc.expectedAnswer(), tc.category(), tc.enabled());
    }

    public void updateCase(EvalTestCase tc) {
        jdbc.update("""
                UPDATE eval_test_case
                   SET question = ?, expected_sql = ?, expected_answer = ?, category = ?, enabled = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tc_id = ?
                """, tc.question(), tc.expectedSql(), tc.expectedAnswer(), tc.category(), tc.enabled(), tc.tcId());
    }

    public int countCases() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM eval_test_case", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------------- 배치 ----------------

    public void insertBatch(String batchId, String requestedBy, int totalCount) {
        jdbc.update("""
                INSERT INTO eval_batch (batch_id, requested_by, started_at, status, total_count,
                                        completed_count, passed_count, pass_rate)
                VALUES (?, ?, ?, 'RUNNING', ?, 0, 0, 0)
                """, batchId, requestedBy, Timestamp.from(Instant.now()), totalCount);
    }

    public void updateProgress(String batchId, int completed, int passed) {
        jdbc.update("""
                UPDATE eval_batch SET completed_count = ?, passed_count = ?,
                       pass_rate = CASE WHEN total_count = 0 THEN 0 ELSE ? * 100.0 / total_count END
                 WHERE batch_id = ?
                """, completed, passed, passed, batchId);
    }

    public void finishBatch(String batchId, String status, int total, int completed, int passed, String note) {
        double passRate = total == 0 ? 0 : (double) passed * 100.0 / total;
        jdbc.update("""
                UPDATE eval_batch
                   SET status = ?, finished_at = ?, total_count = ?, completed_count = ?, passed_count = ?,
                       pass_rate = ?, note = ?
                 WHERE batch_id = ?
                """, status, Timestamp.from(Instant.now()), total, completed, passed, passRate, note, batchId);
    }

    public Optional<EvalBatch> findBatch(String batchId) {
        return jdbc.query("""
                SELECT batch_id, requested_by, started_at, finished_at, status, total_count,
                       completed_count, passed_count, pass_rate, note
                FROM eval_batch WHERE batch_id = ?
                """, (rs, rowNum) -> mapBatch(rs), batchId).stream().findFirst();
    }

    public List<EvalBatch> findBatches(int limit) {
        return jdbc.query("""
                SELECT batch_id, requested_by, started_at, finished_at, status, total_count,
                       completed_count, passed_count, pass_rate, note
                FROM eval_batch ORDER BY started_at DESC LIMIT ?
                """, (rs, rowNum) -> mapBatch(rs), limit);
    }

    // ---------------- 결과 ----------------

    public void insertResult(EvalCaseResult result) {
        jdbc.update("""
                INSERT INTO eval_case_result
                    (batch_id, tc_id, agent_run_id, actual_sql, actual_result, final_answer,
                     question_snapshot, expected_sql_snapshot, expected_answer_snapshot,
                     ex_pass, ex_reason, aa_pass, aa_reason, fn_pass, fn_reason,
                     exec_pass, exec_reason, overall_pass, overall_reason, duration_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, result.batchId(), result.tcId(), result.agentRunId(), result.actualSql(),
                result.actualResult(), result.finalAnswer(), result.questionSnapshot(),
                result.expectedSqlSnapshot(), result.expectedAnswerSnapshot(),
                result.exPass(), result.exReason(), result.aaPass(), result.aaReason(),
                result.fnPass(), result.fnReason(), result.execPass(), result.execReason(),
                result.overallPass(), result.overallReason(), result.durationMs());
    }

    public List<EvalCaseResult> findResults(String batchId) {
        return jdbc.query("""
                SELECT * FROM eval_case_result WHERE batch_id = ? ORDER BY tc_id
                """, (rs, rowNum) -> mapResult(rs), batchId);
    }

    public List<EvalCaseResult> findCaseHistory(String tcId, int limit) {
        return jdbc.query("""
                SELECT * FROM eval_case_result WHERE tc_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> mapResult(rs), tcId, limit);
    }

    // ---------------- 매핑 ----------------

    private EvalTestCase mapCase(ResultSet rs) throws SQLException {
        return new EvalTestCase(
                rs.getString("tc_id"), rs.getString("question"), rs.getString("expected_sql"),
                rs.getString("expected_answer"), rs.getString("category"), rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("updated_at")));
    }

    private EvalBatch mapBatch(ResultSet rs) throws SQLException {
        return new EvalBatch(
                rs.getString("batch_id"), rs.getString("requested_by"),
                toInstant(rs.getTimestamp("started_at")), toInstant(rs.getTimestamp("finished_at")),
                rs.getString("status"), rs.getInt("total_count"), rs.getInt("completed_count"),
                rs.getInt("passed_count"), rs.getDouble("pass_rate"), rs.getString("note"));
    }

    private EvalCaseResult mapResult(ResultSet rs) throws SQLException {
        return new EvalCaseResult(
                rs.getString("batch_id"), rs.getString("tc_id"), rs.getString("agent_run_id"),
                rs.getString("actual_sql"), rs.getString("actual_result"), rs.getString("final_answer"),
                rs.getString("question_snapshot"), rs.getString("expected_sql_snapshot"),
                rs.getString("expected_answer_snapshot"),
                rs.getBoolean("ex_pass"), rs.getString("ex_reason"),
                rs.getBoolean("aa_pass"), rs.getString("aa_reason"),
                rs.getBoolean("fn_pass"), rs.getString("fn_reason"),
                rs.getBoolean("exec_pass"), rs.getString("exec_reason"),
                rs.getBoolean("overall_pass"), rs.getString("overall_reason"),
                rs.getLong("duration_ms"), toInstant(rs.getTimestamp("created_at")));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
