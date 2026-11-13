package com.samsung.gsre.agent.mcp;

import com.samsung.gsre.agent.config.AgentProperties;
import java.sql.Clob;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * INBOUND 원천 데이터 리더: {@code svcm_tb_mcp_api_master.source_query} 를 실행해 내부 로우를 얻는다.
 *
 * <p>이 클래스가 "내부 DB" 와 "MCP 어댑터" 사이의 유일한 접점이다. 여기서 나온 로우는
 * 곧바로 {@link DataReshaper} 로 넘어가 외부 도구 규격(JSON)으로 재가공된다.</p>
 *
 * <p>안전장치: SELECT/WITH 단일 문장만 허용하고, 파라미터는 named parameter 로만 바인딩한다.
 * 결과 행 수는 {@code agent.mcp.max-source-rows} 로 제한한다(로그·메모리 보호).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSourceQueryReader {

    private static final Pattern SELECT_ONLY = Pattern.compile("(?is)^\\s*(select|with)\\b.*");
    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke)\\b");

    private final NamedParameterJdbcTemplate jdbc;
    private final AgentProperties props;

    public List<Map<String, Object>> read(McpApiMaster api, Map<String, Object> params) {
        String sql = validate(api);
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (params != null) {
            params.forEach(source::addValue);
        }

        List<Map<String, Object>> rows = jdbc.query(sql, source, (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), normalize(rs.getObject(i)));
            }
            return row;
        });

        int maxRows = Math.max(1, props.getMcp().getMaxSourceRows());
        if (rows.size() > maxRows) {
            log.warn("[mcp] 원천 행 수 상한 초과 apiId={} rows={} → {} 로 제한", api.apiId(), rows.size(), maxRows);
            return new ArrayList<>(rows.subList(0, maxRows));
        }
        log.info("[mcp] 원천 로우 조회 apiId={} rows={}", api.apiId(), rows.size());
        return rows;
    }

    private String validate(McpApiMaster api) {
        if (!api.hasSourceQuery()) {
            throw new IllegalArgumentException(
                    "INBOUND/BIDIRECTIONAL API 는 source_query 가 필요합니다: " + api.apiId());
        }
        String sql = api.sourceQuery().trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("source_query 는 단일 SELECT 문만 허용합니다: " + api.apiId());
        }
        if (!SELECT_ONLY.matcher(sql).matches()) {
            throw new IllegalArgumentException("source_query 는 SELECT/WITH 로 시작해야 합니다: " + api.apiId());
        }
        if (WRITE_KEYWORD.matcher(sql).find()) {
            throw new IllegalArgumentException("source_query 에 변경 구문 키워드가 있습니다: " + api.apiId());
        }
        return sql;
    }

    /** JSON 으로 나갈 값은 DB 타입 그대로 두면 지저분해진다. 날짜/CLOB 만 문자열로 정규화한다. */
    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), 4_000));
            } catch (Exception e) {
                return null;
            }
        }
        return value;
    }
}
