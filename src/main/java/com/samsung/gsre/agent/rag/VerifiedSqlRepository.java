package com.samsung.gsre.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.llm.JsonSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 검증 SQL 캐시 저장소 (verified_sql_cache). */
@Repository
public class VerifiedSqlRepository {

    private static final ObjectMapper MAPPER = JsonSupport.mapper();
    private static final TypeReference<Map<String, String>> PARAM_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;

    public VerifiedSqlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VerifiedSqlEntry> findAll() {
        return jdbc.query("""
                SELECT id, question, sql_text, tables_csv, parameters_json, verified_by, notes,
                       embedding_json, tokens, use_count, created_at
                FROM verified_sql_cache
                ORDER BY id
                """, (rs, rowNum) -> map(rs));
    }

    public Optional<VerifiedSqlEntry> findById(long id) {
        List<VerifiedSqlEntry> rows = jdbc.query("""
                SELECT id, question, sql_text, tables_csv, parameters_json, verified_by, notes,
                       embedding_json, tokens, use_count, created_at
                FROM verified_sql_cache WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);
        return rows.stream().findFirst();
    }

    public long insert(VerifiedSqlEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO verified_sql_cache
                        (question, sql_text, tables_csv, parameters_json, verified_by, notes,
                         embedding_json, tokens, use_count, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setString(1, entry.question());
            ps.setString(2, entry.sql());
            ps.setString(3, String.join(",", entry.tables()));
            ps.setString(4, writeJson(entry.parameters()));
            ps.setString(5, entry.verifiedBy());
            ps.setString(6, entry.notes());
            ps.setString(7, null);
            ps.setString(8, entry.tokens());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    public void updateEmbedding(long id, String embeddingJson) {
        jdbc.update("UPDATE verified_sql_cache SET embedding_json = ? WHERE id = ?", embeddingJson, id);
    }

    public void incrementUse(long id) {
        jdbc.update("UPDATE verified_sql_cache SET use_count = use_count + 1 WHERE id = ?", id);
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM verified_sql_cache", Integer.class);
        return count == null ? 0 : count;
    }

    private VerifiedSqlEntry map(ResultSet rs) throws SQLException {
        List<String> tables = new ArrayList<>();
        String csv = rs.getString("tables_csv");
        if (csv != null && !csv.isBlank()) {
            for (String t : csv.split(",")) {
                if (!t.isBlank()) {
                    tables.add(t.trim());
                }
            }
        }
        Map<String, String> params = readParams(rs.getString("parameters_json"));
        float[] embedding = parseEmbedding(rs.getString("embedding_json"));
        Timestamp created = rs.getTimestamp("created_at");
        return new VerifiedSqlEntry(
                rs.getLong("id"),
                rs.getString("question"),
                rs.getString("sql_text"),
                tables,
                params,
                rs.getString("verified_by"),
                rs.getString("notes"),
                embedding,
                rs.getString("tokens"),
                rs.getInt("use_count"),
                created == null ? java.time.Instant.now() : created.toInstant());
    }

    private Map<String, String> readParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, PARAM_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private float[] parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
