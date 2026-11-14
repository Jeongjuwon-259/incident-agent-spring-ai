package com.samsung.gsre.agent.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** MCP/REST payload 감사 로그 저장소. 실패한 호출도 반드시 1행 남긴다. */
@Repository
@RequiredArgsConstructor
public class McpPayloadLogRepository {

    private static final String COLUMNS = """
            log_id, call_id, run_id, api_id, direction, transport_type, http_method,
            raw_payload_in, reshaped_payload_in, raw_payload_out, reshaped_payload_out,
            status_code, duration_ms, error_message, created_at
            """;

    private final JdbcTemplate jdbc;

    public long insert(McpPayloadLog log) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO svcm_tb_mcp_payload_log
                        (call_id, run_id, api_id, direction, transport_type, http_method,
                         raw_payload_in, reshaped_payload_in,
                         raw_payload_out, reshaped_payload_out, status_code, duration_ms, error_message, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)
                    """, new String[]{"log_id"});
            ps.setString(1, log.callId());
            ps.setString(2, log.runId());
            ps.setString(3, log.apiId());
            ps.setString(4, log.direction());
            ps.setString(5, log.transportType());
            ps.setString(6, log.httpMethod());
            ps.setString(7, log.rawPayloadIn());
            ps.setString(8, log.reshapedPayloadIn());
            ps.setString(9, log.rawPayloadOut());
            ps.setString(10, log.reshapedPayloadOut());
            if (log.statusCode() == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, log.statusCode());
            }
            ps.setLong(12, log.durationMs());
            ps.setString(13, log.errorMessage());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    public List<McpPayloadLog> findRecent(String apiId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (apiId == null || apiId.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS
                    + " FROM svcm_tb_mcp_payload_log ORDER BY log_id DESC LIMIT ?",
                    (rs, rowNum) -> map(rs), safeLimit);
        }
        return jdbc.query("SELECT " + COLUMNS
                + " FROM svcm_tb_mcp_payload_log WHERE api_id = ? ORDER BY log_id DESC LIMIT ?",
                (rs, rowNum) -> map(rs), apiId, safeLimit);
    }

    public List<McpPayloadLog> findByCallId(String callId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM svcm_tb_mcp_payload_log WHERE call_id = ?",
                (rs, rowNum) -> map(rs), callId);
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM svcm_tb_mcp_payload_log", Integer.class);
        return count == null ? 0 : count;
    }

    private McpPayloadLog map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        int status = rs.getInt("status_code");
        Integer statusCode = rs.wasNull() ? null : status;
        return new McpPayloadLog(
                rs.getLong("log_id"),
                rs.getString("call_id"),
                rs.getString("run_id"),
                rs.getString("api_id"),
                rs.getString("direction"),
                rs.getString("raw_payload_in"),
                rs.getString("reshaped_payload_in"),
                rs.getString("raw_payload_out"),
                rs.getString("reshaped_payload_out"),
                statusCode,
                rs.getLong("duration_ms"),
                rs.getString("error_message"),
                created == null ? Instant.now() : created.toInstant(),
                rs.getString("transport_type"),
                rs.getString("http_method"));
    }
}
