package com.samsung.gsre.agent.mcp.repository;

import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.McpDirection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MCP/REST API 마스터 저장소. 등록/조회만 담당하고 판단은 Orchestrator 가 한다. */
@Repository
@RequiredArgsConstructor
public class McpMasterRepository {

    private static final String COLUMNS = """
            api_id, api_name, tool_name, direction, transport_type, endpoint_url, http_method, headers_config,
            auth_type, auth_config, request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
            timeout_ms, active_yn, description, created_by, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public List<McpApiMaster> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM svcm_tb_mcp_api_master ORDER BY api_id",
                (rs, rowNum) -> map(rs));
    }

    public Optional<McpApiMaster> findById(String apiId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM svcm_tb_mcp_api_master WHERE api_id = ?",
                (rs, rowNum) -> map(rs), apiId).stream().findFirst();
    }

    /** 실행 대상은 활성(Y) 행만. 비활성 도구는 "없는 도구" 로 취급한다. */
    public Optional<McpApiMaster> findActive(String apiId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM svcm_tb_mcp_api_master WHERE api_id = ? AND active_yn = 'Y'",
                (rs, rowNum) -> map(rs), apiId).stream().findFirst();
    }

    public void insert(McpApiMaster api) {
        jdbc.update("""
                INSERT INTO svcm_tb_mcp_api_master
                    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, http_method,
                     headers_config, auth_type, auth_config, request_schema, request_reshaping_rule,
                     response_reshaping_rule, source_query, timeout_ms, active_yn, description, created_by,
                     created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                api.apiId(), api.apiName(), api.toolName(), api.direction().name(), api.transportType(),
                api.endpointUrl(), api.httpMethod(), api.headersConfig(),
                api.authType(), api.authConfig(), api.requestSchema(),
                api.requestReshapingRule(), api.responseReshapingRule(), api.sourceQuery(),
                api.timeoutMs(), api.active() ? "Y" : "N", api.description(), api.createdBy());
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM svcm_tb_mcp_api_master", Integer.class);
        return count == null ? 0 : count;
    }

    private McpApiMaster map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new McpApiMaster(
                rs.getString("api_id"),
                rs.getString("api_name"),
                rs.getString("tool_name"),
                McpDirection.from(rs.getString("direction")),
                rs.getString("transport_type"),
                rs.getString("endpoint_url"),
                rs.getString("auth_type"),
                rs.getString("auth_config"),
                rs.getString("request_schema"),
                rs.getString("request_reshaping_rule"),
                rs.getString("response_reshaping_rule"),
                rs.getString("source_query"),
                rs.getInt("timeout_ms"),
                "Y".equalsIgnoreCase(rs.getString("active_yn")),
                rs.getString("description"),
                rs.getString("created_by"),
                created == null ? Instant.now() : created.toInstant(),
                updated == null ? Instant.now() : updated.toInstant(),
                rs.getString("http_method"),
                rs.getString("headers_config"));
    }
}
