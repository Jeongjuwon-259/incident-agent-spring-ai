package com.samsung.gsre.agent.gateway.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.nl2sql.SchemaCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SQL 안전 검증 단위 테스트 (일반 코드로 처리하는 영역). */
class SqlGuardTest {

    private SqlGuard guard;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.getGuard().setMaxRows(200);
        guard = new SqlGuard(new SchemaCatalog(), props);
    }

    @Test
    @DisplayName("정상 SELECT 는 LIMIT 이 보정되어 통과한다")
    void validSelectGetsLimit() {
        GuardVerdict verdict = guard
                .validate("SELECT app_id, app_name FROM svcm_tb_app_master WHERE app_id = 'APP-1001'");

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.sql()).containsIgnoringCase("LIMIT 200");
        assertThat(verdict.tables()).containsExactly("svcm_tb_app_master");
    }

    @Test
    @DisplayName("UPDATE/DELETE 등 변경 구문은 재시도 불가로 차단한다")
    void rejectsDml() {
        GuardVerdict verdict = guard.validate("UPDATE svcm_tb_app_master SET status = 'X'");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.repairable()).isFalse();
    }

    @Test
    @DisplayName("허용 목록에 없는 테이블은 차단한다")
    void rejectsUnknownTable() {
        GuardVerdict verdict = guard.validate("SELECT * FROM some_secret_table");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("허용되지 않은 테이블"));
    }

    @Test
    @DisplayName("사용자 마스터 SELECT * 는 재시도 가능한 위반으로 막는다")
    void rejectsSelectStarOnUserMaster() {
        GuardVerdict verdict = guard.validate("SELECT * FROM svcm_tb_user_info_master");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.repairable()).isTrue();
    }

    @Test
    @DisplayName("사용자 마스터도 필요한 컬럼만 명시하면 PII 마스킹과 함께 허용된다")
    void allowsExplicitColumnsWithMasking() {
        GuardVerdict verdict = guard.validate("""
                SELECT u.user_id, u.user_name, u.email
                FROM svcm_tb_user_info_master u
                JOIN svcm_tb_app_user_map m ON m.user_id = u.user_id
                WHERE m.app_id = 'APP-1001'
                """);

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.maskPii()).isTrue();
    }

    @Test
    @DisplayName("다중 문장/주석은 차단한다")
    void rejectsMultipleStatementsAndComments() {
        assertThat(guard.validate("SELECT 1 FROM svcm_tb_app_master; DROP TABLE svcm_tb_app_master").valid())
                .isFalse();
        assertThat(guard.validate("SELECT app_id FROM svcm_tb_app_master -- comment").valid()).isFalse();
    }

    @Test
    @DisplayName("LIMIT 이 최대 행 수를 넘으면 최대값으로 보정한다")
    void clampsLimit() {
        GuardVerdict verdict = guard.validate("SELECT app_id FROM svcm_tb_app_master LIMIT 5000");

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.sql()).containsIgnoringCase("LIMIT 200");
    }

    @Test
    @DisplayName("WITH 절의 CTE 이름은 테이블 허용 목록 검사에서 제외한다")
    void allowsCteName() {
        GuardVerdict verdict = guard.validate("""
                WITH recent AS (SELECT app_id FROM svcm_tb_app_master)
                SELECT app_id FROM recent
                """);

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.tables()).containsExactly("svcm_tb_app_master");
    }

    @Test
    @DisplayName("FROM 절 콤마(묵시적 조인)는 허용 목록 우회를 막기 위해 차단한다")
    void rejectsImplicitJoin() {
        GuardVerdict verdict = guard.validate(
                "SELECT a.app_id FROM svcm_tb_app_master a, svcm_tb_user_info_master u");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("JOIN"));
    }

    @Test
    @DisplayName("SELECT ... INTO 는 변경성 구문으로 차단한다")
    void rejectsSelectInto() {
        GuardVerdict verdict = guard.validate(
                "SELECT app_id INTO OUTFILE '/tmp/x' FROM svcm_tb_app_master");

        assertThat(verdict.valid()).isFalse();
    }
}
