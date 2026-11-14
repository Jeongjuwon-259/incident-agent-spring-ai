package com.samsung.gsre.agent.config.seed;

import com.samsung.gsre.agent.rag.VerifiedSqlEntry;
import com.samsung.gsre.agent.rag.repository.VerifiedSqlRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 데모용 기준 데이터 시더.
 *
 * <ul>
 *   <li>8대 테이블에 실제 업무 형태의 샘플 데이터를 넣는다 (이미 데이터가 있으면 건너뛴다).</li>
 *   <li>검증 SQL 캐시 6건을 'SEED-GOLDEN' 검증자로 등록한다.</li>
 *   <li>품질 평가 골든셋 6건을 등록한다.</li>
 * </ul>
 * 운영에서는 {@code agent.demo.seed=false} 로 끈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.demo.seed", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final VerifiedSqlRepository cacheRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedBusinessData();
        seedVerifiedSql();
        seedEvalCases();
    }

    private void seedBusinessData() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM svcm_tb_app_master", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        log.info("[seed] 데모 기준 데이터 생성");

        jdbc.update("INSERT INTO svcm_tb_dept_info_master (dept_id, dept_name, parent_dept_id, dept_head_name, org_code) VALUES (?,?,?,?,?)",
                "D1001", "SRE플랫폼팀", null, "김민수", "SRE-01");
        jdbc.update("INSERT INTO svcm_tb_dept_info_master (dept_id, dept_name, parent_dept_id, dept_head_name, org_code) VALUES (?,?,?,?,?)",
                "D1002", "인프라운영팀", null, "이서연", "IOP-02");
        jdbc.update("INSERT INTO svcm_tb_dept_info_master (dept_id, dept_name, parent_dept_id, dept_head_name, org_code) VALUES (?,?,?,?,?)",
                "D1003", "서비스데스크", null, "박지훈", "SD-03");
        jdbc.update("INSERT INTO svcm_tb_dept_info_master (dept_id, dept_name, parent_dept_id, dept_head_name, org_code) VALUES (?,?,?,?,?)",
                "D1004", "보안운영팀", null, "최하늘", "SEC-04");

        jdbc.update("INSERT INTO svcm_tb_service_map_master (service_id, service_name, domain_name, description) VALUES (?,?,?,?)",
                "SVC-01", "서비스맵포털", "svcm.samsung.com", "서비스맵/자산 정보 포털");
        jdbc.update("INSERT INTO svcm_tb_service_map_master (service_id, service_name, domain_name, description) VALUES (?,?,?,?)",
                "SVC-02", "AI에이전트플랫폼", "agent.samsung.com", "장애 지원 AI Agent 플랫폼");
        jdbc.update("INSERT INTO svcm_tb_service_map_master (service_id, service_name, domain_name, description) VALUES (?,?,?,?)",
                "SVC-03", "배포관리", "deploy.samsung.com", "배포 파이프라인 관리");
        jdbc.update("INSERT INTO svcm_tb_service_map_master (service_id, service_name, domain_name, description) VALUES (?,?,?,?)",
                "SVC-04", "자산관리서비스", "asset.samsung.com", "IT 자산 등록/조회");
        jdbc.update("INSERT INTO svcm_tb_service_map_master (service_id, service_name, domain_name, description) VALUES (?,?,?,?)",
                "SVC-05", "모니터링서비스", "monitor.samsung.com", "인프라 관제/알람");

        insertApp("APP-1001", "서비스맵포털", "WEB", "SVC-01", "D1001", "OPERATING", "CRITICAL", "서비스맵 포털 본체");
        insertApp("APP-1002", "장애지원에이전트", "WEB", "SVC-02", "D1001", "OPERATING", "CRITICAL", "장애 지원 AI Agent");
        insertApp("APP-1003", "배포관리콘솔", "WEB", "SVC-03", "D1002", "OPERATING", "HIGH", "배포 관리 콘솔");
        insertApp("APP-1004", "자산관리포털", "WEB", "SVC-04", "D1002", "OPERATING", "MEDIUM", "IT 자산 포털");
        insertApp("APP-1005", "모니터링게이트웨이", "API", "SVC-05", "D1003", "OPERATING", "HIGH", "관제 데이터 수집 API");
        insertApp("APP-1006", "권한신청시스템", "WEB", "SVC-02", "D1004", "MAINTENANCE", "MEDIUM", "계정 권한 신청");

        insertUser("U1001", "김민수", "kim.minsu@samsung.com", "010-1234-5678", "D1001", "S12345", "팀장");
        insertUser("U1002", "이서연", "lee.seoyeon@samsung.com", "010-2345-6789", "D1002", "S23456", "팀장");
        insertUser("U1003", "박지훈", "park.jihoon@samsung.com", "010-3456-7890", "D1003", "S34567", "팀장");
        insertUser("U1004", "최하늘", "choi.haneul@samsung.com", "010-4567-8901", "D1004", "S45678", "팀장");
        insertUser("U1005", "한도윤", "han.doyun@samsung.com", "010-5678-9012", "D1001", "S56789", "매니저");
        insertUser("U1006", "오세린", "oh.serin@samsung.com", "010-6789-0123", "D1002", "S67890", "엔지니어");
        insertUser("U1007", "문지아", "moon.jia@samsung.com", "010-7890-1234", "D1003", "S78901", "엔지니어");
        insertUser("U1008", "배준호", "bae.junho@samsung.com", "010-8901-2345", "D1001", "S89012", "엔지니어");

        insertMap("M2001", "APP-1001", "U1001", "OWNER", "2025-03-02");
        insertMap("M2002", "APP-1001", "U1005", "OPERATOR", "2025-04-11");
        insertMap("M2003", "APP-1001", "U1007", "VIEWER", "2025-06-01");
        insertMap("M2004", "APP-1001", "U1008", "VIEWER", "2025-08-19");
        insertMap("M2005", "APP-1002", "U1005", "OWNER", "2025-05-10");
        insertMap("M2006", "APP-1002", "U1001", "OPERATOR", "2025-05-10");
        insertMap("M2007", "APP-1002", "U1006", "OPERATOR", "2025-07-22");
        insertMap("M2008", "APP-1002", "U1008", "VIEWER", "2025-09-03");
        insertMap("M2009", "APP-1003", "U1002", "OWNER", "2025-02-14");
        insertMap("M2010", "APP-1003", "U1006", "OPERATOR", "2025-02-14");
        insertMap("M2011", "APP-1003", "U1007", "VIEWER", "2025-10-01");
        insertMap("M2012", "APP-1004", "U1002", "OWNER", "2025-01-20");
        insertMap("M2013", "APP-1004", "U1006", "VIEWER", "2025-03-15");
        insertMap("M2014", "APP-1005", "U1003", "OWNER", "2025-04-05");
        insertMap("M2015", "APP-1005", "U1007", "OPERATOR", "2025-04-05");
        insertMap("M2016", "APP-1006", "U1004", "OWNER", "2025-06-30");
        insertMap("M2017", "APP-1006", "U1008", "VIEWER", "2025-06-30");

        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1001", "APP-1001", "PASS", "APPROVED", "2026-10-15", "U1001", "최종 검수 완료");
        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1002", "APP-1002", "PASS", "APPROVED", "2026-10-20", "U1001", "최종 검수 완료");
        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1003", "APP-1003", "PASS", "APPROVED", "2026-09-30", "U1002", "최종 검수 완료");
        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1004", "APP-1004", "FAIL", "PENDING", null, "U1002", "설명 보완 필요");
        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1005", "APP-1005", "PASS", "APPROVED", "2026-11-01", "U1003", "최종 검수 완료");
        jdbc.update("INSERT INTO svcm_tb_svc_master (svc_id, app_id, inspect_status, approve_status, approve_date, reviewer_id, user_input_value) VALUES (?,?,?,?,?,?,?)",
                "SVC-1006", "APP-1006", "PENDING", "PENDING", null, "U1004", "검수 대기");

        jdbc.update("INSERT INTO svcm_tb_app_alias (alias_id, app_id, alias_name, alias_type) VALUES (?,?,?,?)",
                "AL001", "APP-1001", "서비스맵", "OFFICIAL");
        jdbc.update("INSERT INTO svcm_tb_app_alias (alias_id, app_id, alias_name, alias_type) VALUES (?,?,?,?)",
                "AL002", "APP-1001", "서비스맵포털", "SHORT");
        jdbc.update("INSERT INTO svcm_tb_app_alias (alias_id, app_id, alias_name, alias_type) VALUES (?,?,?,?)",
                "AL003", "APP-1002", "장애지원에이전트", "OFFICIAL");
        jdbc.update("INSERT INTO svcm_tb_app_alias (alias_id, app_id, alias_name, alias_type) VALUES (?,?,?,?)",
                "AL004", "APP-1002", "AI에이전트", "SHORT");
        jdbc.update("INSERT INTO svcm_tb_app_alias (alias_id, app_id, alias_name, alias_type) VALUES (?,?,?,?)",
                "AL005", "APP-1003", "배포콘솔", "LEGACY");

        insertIssue("INC-1042", "SVCM", "APP-1003", "U1002", "INCIDENT", "DONE", "배포관리콘솔 배포 실패 대응", "3.0", "2026-11-07");
        insertIssue("INC-1045", "SVCM", "APP-1001", "U1001", "INCIDENT", "DONE", "서비스맵 포털 로그인 지연", "2.5", "2026-11-08");
        insertIssue("INC-1049", "SVCM", "APP-1004", "U1002", "INCIDENT", "DONE", "자산관리포털 조회 오류", "2.0", "2026-11-09");
        insertIssue("INC-1051", "SVCM", "APP-1003", "U1006", "INCIDENT", "DONE", "배포관리콘솔 큐 적체", "6.5", "2026-11-10");
        insertIssue("CHG-2087", "SVCM", "APP-1002", "U1005", "CHANGE", "DONE", "장애지원 에이전트 프롬프트 배포", "4.0", "2026-11-11");
        insertIssue("INC-1055", "SVCM", "APP-1005", "U1003", "INCIDENT", "IN_PROGRESS", "모니터링 게이트웨이 알람 누락", "1.5", "2026-11-12");
    }

    private void seedVerifiedSql() {
        if (cacheRepository.count() > 0) {
            return;
        }
        log.info("[seed] 검증 SQL 캐시 6건 등록 (verifiedBy=SEED-GOLDEN)");
        register("APP-1001 서비스맵포털 담당자 알려줘",
                """
                SELECT a.app_id, a.app_name, u.user_name, d.dept_name, u.email, u.phone
                FROM svcm_tb_app_master a
                JOIN svcm_tb_app_user_map m ON m.app_id = a.app_id AND m.role_code = 'OWNER'
                JOIN svcm_tb_user_info_master u ON u.user_id = m.user_id
                LEFT JOIN svcm_tb_dept_info_master d ON d.dept_id = u.dept_id
                WHERE a.app_id = :appId
                LIMIT 20
                """,
                Map.of("appId", "APP-1001"),
                List.of("svcm_tb_app_master", "svcm_tb_app_user_map", "svcm_tb_user_info_master", "svcm_tb_dept_info_master"),
                "담당자(OWNER) 표준 조회");
        register("APP-1002 장애지원에이전트를 사용하는 사용자는 몇 명인가",
                """
                SELECT COUNT(*) AS user_count
                FROM svcm_tb_app_user_map m
                WHERE m.app_id = :appId
                LIMIT 10
                """,
                Map.of("appId", "APP-1002"),
                List.of("svcm_tb_app_user_map"),
                "APP 사용자 수 집계");
        register("SRE플랫폼팀 부서장은 누구인가",
                """
                SELECT d.dept_id, d.dept_name, d.dept_head_name, d.org_code
                FROM svcm_tb_dept_info_master d
                WHERE d.dept_name LIKE :deptName
                LIMIT 20
                """,
                Map.of("deptName", "%SRE플랫폼팀%"),
                List.of("svcm_tb_dept_info_master"),
                "부서장 조회");
        register("APP-1003 배포관리콘솔 관련 Jira 작업이력 보여줘",
                """
                SELECT v.issue_key, v.app_id, v.app_name, v.assignee_name, v.assignee_dept,
                       v.issue_type, v.status, v.summary, v.worklog_hours, v.worklog_date
                FROM svcm_vw_jira_dri_worklog v
                WHERE v.app_id = :appId
                ORDER BY v.worklog_date DESC
                LIMIT 10
                """,
                Map.of("appId", "APP-1003"),
                List.of("svcm_vw_jira_dri_worklog"),
                "APP 별 Jira 작업이력");
        register("서비스맵 별칭을 가진 APP ID는 무엇인가",
                """
                SELECT al.alias_id, al.app_id, a.app_name, al.alias_name, al.alias_type
                FROM svcm_tb_app_alias al
                JOIN svcm_tb_app_master a ON a.app_id = al.app_id
                WHERE al.alias_name LIKE :aliasName
                ORDER BY al.alias_type
                LIMIT 20
                """,
                Map.of("aliasName", "%서비스맵%"),
                List.of("svcm_tb_app_alias", "svcm_tb_app_master"),
                "별칭 정본 조회");
        register("APP-1004 자산관리포털의 서비스 도메인은 무엇인가",
                """
                SELECT a.app_id, a.app_name, s.service_id, s.service_name, s.domain_name
                FROM svcm_tb_app_master a
                LEFT JOIN svcm_tb_service_map_master s ON s.service_id = a.service_id
                WHERE a.app_id = :appId
                LIMIT 20
                """,
                Map.of("appId", "APP-1004"),
                List.of("svcm_tb_app_master", "svcm_tb_service_map_master"),
                "서비스맵/도메인 조회");
    }

    private void register(String question, String sql, Map<String, String> params,
                          List<String> tables, String notes) {
        cacheRepository.insert(new VerifiedSqlEntry(0L, question, sql.strip(), tables, params,
                "SEED-GOLDEN", notes, null, null, 0, Instant.now()));
    }

    private void seedEvalCases() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM eval_test_case", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        log.info("[seed] 품질 평가 골든셋 6건 등록");

        insertCase("TC-001", "APP-1001 서비스맵포털 담당자 알려줘",
                """
                SELECT a.app_id, a.app_name, u.user_name, d.dept_name, u.email, u.phone
                FROM svcm_tb_user_info_master u
                JOIN svcm_tb_app_user_map m ON m.user_id = u.user_id AND m.role_code = 'OWNER'
                JOIN svcm_tb_app_master a ON a.app_id = m.app_id
                LEFT JOIN svcm_tb_dept_info_master d ON d.dept_id = u.dept_id
                WHERE a.app_id IN ('APP-1001')
                LIMIT 50
                """,
                "김민수(SRE플랫폼팀)", "CONTACT");
        insertCase("TC-002", "APP-1002 장애지원에이전트를 사용하는 사용자는 몇 명인가",
                """
                SELECT COUNT(*) AS user_count
                FROM svcm_tb_app_user_map
                WHERE app_id = 'APP-1002'
                """,
                "사용자는 4명입니다", "COUNT");
        insertCase("TC-003", "SRE플랫폼팀 부서장은 누구인가",
                """
                SELECT dept_id, dept_name, dept_head_name, org_code
                FROM svcm_tb_dept_info_master
                WHERE dept_name = 'SRE플랫폼팀'
                """,
                "김민수", "DEPT");
        insertCase("TC-004", "APP-1003 배포관리콘솔 관련 Jira 작업이력 보여줘",
                """
                SELECT issue_key, app_id, app_name, assignee_name, assignee_dept,
                       issue_type, status, summary, worklog_hours, worklog_date
                FROM svcm_vw_jira_dri_worklog
                WHERE app_id = 'APP-1003'
                ORDER BY worklog_date DESC
                LIMIT 10
                """,
                "INC-1051, INC-1042", "JIRA_WORKLOG");
        insertCase("TC-005", "서비스맵 별칭을 가진 APP ID는 무엇인가",
                """
                SELECT al.alias_id, al.app_id, a.app_name, al.alias_name, al.alias_type
                FROM svcm_tb_app_alias al
                JOIN svcm_tb_app_master a ON a.app_id = al.app_id
                WHERE al.alias_name IN ('서비스맵', '서비스맵포털')
                """,
                "APP-1001", "ALIAS");
        insertCase("TC-006", "APP-1004 자산관리포털의 서비스 도메인은 무엇인가",
                """
                SELECT a.app_id, a.app_name, s.service_id, s.service_name, s.domain_name
                FROM svcm_tb_service_map_master s
                JOIN svcm_tb_app_master a ON a.service_id = s.service_id
                WHERE a.app_id = 'APP-1004'
                """,
                "SVC-04 자산관리서비스", "SERVICE_MAP");
    }

    private void insertCase(String tcId, String question, String expectedSql, String expectedAnswer, String category) {
        jdbc.update("""
                INSERT INTO eval_test_case (tc_id, question, expected_sql, expected_answer, category, enabled)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, tcId, question, expectedSql.strip(), expectedAnswer, category);
    }

    private void insertApp(String id, String name, String type, String serviceId, String deptId,
                           String status, String criticality, String description) {
        jdbc.update("""
                INSERT INTO svcm_tb_app_master
                    (app_id, app_name, app_type, service_id, dept_id, status, criticality, description)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, name, type, serviceId, deptId, status, criticality, description);
    }

    private void insertUser(String id, String name, String email, String phone, String deptId,
                            String empNo, String jobTitle) {
        jdbc.update("""
                INSERT INTO svcm_tb_user_info_master (user_id, user_name, email, phone, dept_id, emp_no, job_title)
                VALUES (?,?,?,?,?,?,?)
                """, id, name, email, phone, deptId, empNo, jobTitle);
    }

    private void insertMap(String id, String appId, String userId, String role, String grantedAt) {
        jdbc.update("INSERT INTO svcm_tb_app_user_map (map_id, app_id, user_id, role_code, granted_at) VALUES (?,?,?,?,?)",
                id, appId, userId, role, grantedAt);
    }

    private void insertIssue(String key, String project, String appId, String assignee, String type,
                             String status, String summary, String hours, String date) {
        jdbc.update("""
                INSERT INTO svcm_tb_jira_issue
                    (issue_key, project_key, app_id, assignee_id, issue_type, status, summary, worklog_hours, worklog_date)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, key, project, appId, assignee, type, status, summary, new java.math.BigDecimal(hours), date);
    }
}
