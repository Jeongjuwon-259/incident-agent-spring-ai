package com.samsung.gsre.agent.mcp.repository;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.reshaping.DataReshaper;
import com.samsung.gsre.agent.mcp.validation.SourceQueryValidator;
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
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>원천 SQL 은 {@link SourceQueryValidator} 로 읽기 전용 단일 SELECT 임을 확인한다.</li>
 *   <li>파라미터는 named parameter 로만 바인딩한다 (문자열 조립 금지).</li>
 *   <li>행 수 상한은 {@code agent.mcp.max-source-rows}. SQL 에 LIMIT 이 없으면 상한+1 을 붙여
 *       DB 가 무한정 행을 만들지 않게 한다.</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 조회</h2>
 * <ol>
 *   <li>source_query 검증·정규화</li>
 *   <li>파라미터 바인딩 후 실행, 컬럼명 소문자·JSON 친화 타입으로 정규화</li>
 *   <li>상한 초과분 절단</li>
 * </ol>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>{@code List<Map<String,Object>>} — 그대로 {@link DataReshaper} 의 입력이 된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSourceQueryReader {

    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)\\blimit\\b");

    private final NamedParameterJdbcTemplate jdbc;
    private final AgentProperties props;
    private final SourceQueryValidator validator;

    // ==================================================================
    // 중단 (PROCESS) — 조회
    // ==================================================================

    public List<Map<String, Object>> read(McpApiMaster api, Map<String, Object> params) {
        int maxRows = Math.max(1, props.getMcp().getMaxSourceRows());
        String sql = limitGuarded(validator.validate(api), maxRows);

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

        if (rows.size() > maxRows) {
            log.warn("[mcp] 원천 행 수 상한 초과 apiId={} rows={} → {} 로 제한", api.apiId(), rows.size(), maxRows);
            return new ArrayList<>(rows.subList(0, maxRows));
        }
        log.info("[mcp] 원천 로우 조회 apiId={} rows={}", api.apiId(), rows.size());
        return rows;
    }

    // ==================================================================
    // 하단 (OUTPUT) — 결과 정규화·행 상한
    // ==================================================================

    /** SQL 에 LIMIT 이 없으면 상한+1 을 붙인다. (초과 여부를 감지해 경고할 수 있게 1행 더 읽는다) */
    private String limitGuarded(String sql, int maxRows) {
        if (LIMIT_CLAUSE.matcher(sql).find()) {
            return sql;
        }
        return sql + "\nLIMIT " + (maxRows + 1);
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
