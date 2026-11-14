package com.samsung.gsre.agent.mcp.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** INBOUND 원천 SQL 이 읽기 전용 단일 SELECT 인지 검증. */
class SourceQueryValidatorTest {

    private final SourceQueryValidator validator = new SourceQueryValidator();

    @Test
    @DisplayName("정상 SELECT 는 통과하고 끝 세미콜론만 제거한다")
    void acceptsSelect() {
        String sql = validator.validate("API-1", "SELECT app_id FROM svcm_tb_app_master ORDER BY app_id;");

        assertThat(sql).isEqualTo("SELECT app_id FROM svcm_tb_app_master ORDER BY app_id");
    }

    @Test
    @DisplayName("WITH ... SELECT 도 읽기 전용이면 통과한다")
    void acceptsCte() {
        assertThat(validator.validate("API-1",
                "WITH recent AS (SELECT app_id FROM svcm_tb_app_master) SELECT * FROM recent"))
                .startsWith("WITH");
    }

    @Test
    @DisplayName("다중 문장·주석·변경 구문은 차단한다")
    void rejectsWritesAndObfuscation() {
        assertThatThrownBy(() -> validator.validate("API-1",
                "SELECT app_id FROM svcm_tb_app_master; DROP TABLE svcm_tb_app_master"))
                .hasMessageContaining("단일 SELECT");
        assertThatThrownBy(() -> validator.validate("API-1",
                "SELECT app_id FROM svcm_tb_app_master -- 주석"))
                .hasMessageContaining("주석");
        assertThatThrownBy(() -> validator.validate("API-1",
                "WITH x AS (DELETE FROM svcm_tb_app_master) SELECT 1 FROM x"))
                .hasMessageContaining("키워드");
        assertThatThrownBy(() -> validator.validate("API-1",
                "SELECT app_id INTO OUTFILE '/tmp/x' FROM svcm_tb_app_master"))
                .hasMessageContaining("키워드");
        assertThatThrownBy(() -> validator.validate("API-1", "UPDATE svcm_tb_app_master SET status='X'"))
                .hasMessageContaining("SELECT/WITH");
    }

    @Test
    @DisplayName("빈 SQL 은 거부한다")
    void rejectsBlank() {
        assertThatThrownBy(() -> validator.validate("API-1", "  "))
                .hasMessageContaining("비어 있습니다");
    }
}
