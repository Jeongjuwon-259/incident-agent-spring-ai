package com.samsung.gsre.agent.audit.repository;

import com.samsung.gsre.agent.model.ConversationTurn;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 멀티턴 대화 세션 저장소. 최근 N턴을 질의 이해 단계의 맥락으로 넘긴다. */
@Repository
public class ConversationStore {

    private final JdbcTemplate jdbc;

    public ConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConversationTurn> recentTurns(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<ConversationTurn> turns = new ArrayList<>(jdbc.query("""
                SELECT role, content, run_id, created_at
                FROM agent_conversation_turn
                WHERE session_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> new ConversationTurn(
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("run_id"),
                toInstant(rs.getTimestamp("created_at"))), sessionId, limit));
        Collections.reverse(turns);
        return turns;
    }

    public void append(String sessionId, String role, String content, String runId) {
        if (sessionId == null || sessionId.isBlank() || content == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO agent_conversation_turn (session_id, role, content, run_id)
                VALUES (?, ?, ?, ?)
                """, sessionId, role, content, runId);
    }

    private java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? java.time.Instant.now() : timestamp.toInstant();
    }
}
