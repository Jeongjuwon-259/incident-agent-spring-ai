package com.samsung.gsre.agent.gateway.jdbc;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.gateway.exception.SqlExecutionException;
import com.samsung.gsre.agent.gateway.guard.GuardVerdict;
import com.samsung.gsre.agent.gateway.mask.PiiMasker;
import com.samsung.gsre.agent.model.SqlExecutionResult;
import com.samsung.gsre.agent.nl2sql.SchemaCatalog;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL 실행기 (JDBC 저수준 접근을 모아 둔 Worker).
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>읽기 전용 NamedParameterJdbcTemplate, 쿼리 타임아웃·최대 행 수는 {@code agent.guard.*} 설정을 따른다.</li>
 *   <li>결과 컬럼명은 소문자로 정규화하고, 날짜/숫자는 JSON 친화 타입으로 변환한다.</li>
 *   <li>PII 컬럼은 {@link PiiMasker} 로 마스킹한 뒤 반환한다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>SQL + 바인딩 파라미터를 JDBC 로 실행한다.</li>
 *   <li>ResultSetMetaData 로 컬럼 순서를 보존한 행 목록을 만든다.</li>
 *   <li>마스킹 대상 컬럼을 치환해 {@link SqlExecutionResult} 로 반환한다.</li>
 * </ol>
 */
@Slf4j
@Component
public class DbExecutor {

    // ------------------------------------------------------------------
    // 상단: 실행 기준정보
    // ------------------------------------------------------------------

    private final NamedParameterJdbcTemplate namedJdbc;
    private final SchemaCatalog catalog;
    private final AgentProperties props;

    public DbExecutor(javax.sql.DataSource dataSource, SchemaCatalog catalog, AgentProperties props) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(props.getGuard().getQueryTimeoutSeconds());
        jdbcTemplate.setMaxRows(props.getGuard().getMaxRows());
        jdbcTemplate.setFetchSize(100);
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.catalog = catalog;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 중단: 실행
    // ------------------------------------------------------------------

    public SqlExecutionResult execute(GuardVerdict verdict, Map<String, Object> parameters) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        long started = System.nanoTime();
        try {
            SqlExecutionResult raw = namedJdbc.query(verdict.sql(), params, rs -> {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(columns.get(i - 1), normalize(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return new SqlExecutionResult(columns, rows, rows.size(), 0L);
            });
            if (raw == null) {
                return SqlExecutionResult.empty();
            }
            SqlExecutionResult result = verdict.maskPii() ? mask(raw, verdict.tables()) : raw;
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            log.debug("[sql] {} rows in {}ms : {}", result.rowCount(), elapsed, verdict.sql());
            return new SqlExecutionResult(result.columns(), result.rows(), result.rowCount(), elapsed);
        } catch (DataAccessException e) {
            throw new SqlExecutionException(rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------
    // 하단: 결과 정규화·마스킹
    // ------------------------------------------------------------------

    private Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (value instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), 4_000));
            } catch (SQLException e) {
                return null;
            }
        }
        return value;
    }

    private SqlExecutionResult mask(SqlExecutionResult result, List<String> tables) {
        List<Map<String, Object>> masked = new ArrayList<>(result.rows().size());
        for (Map<String, Object> row : result.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.replaceAll((column, value) -> {
                for (String table : tables) {
                    if (catalog.isPii(table, column)) {
                        return PiiMasker.mask(column, value);
                    }
                }
                return value;
            });
            masked.add(copy);
        }
        return new SqlExecutionResult(result.columns(), masked, result.rowCount(), result.elapsedMs());
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        String message = e.getMessage();
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }

}
