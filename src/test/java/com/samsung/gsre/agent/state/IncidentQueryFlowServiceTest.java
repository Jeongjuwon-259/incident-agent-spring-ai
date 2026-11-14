package com.samsung.gsre.agent.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.gsre.agent.audit.repository.AuditRepository;
import com.samsung.gsre.agent.model.RunStatus;
import com.samsung.gsre.agent.state.trace.TraceStep;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 6단계 파이프라인 통합 테스트 (오프라인 모드 = 모델 없이 결정적 동작). */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:flowtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class IncidentQueryFlowServiceTest {

    @Autowired
    private IncidentQueryFlowService flowService;

    @Autowired
    private AuditRepository auditRepository;

    @Test
    @DisplayName("담당자 질의가 6단계를 거쳐 근거 있는 답변을 만든다")
    void answersContactQuestion() {
        PipelineResult result = flowService.handle("u1", "WEB", "s-contact",
                "APP-1001 서비스맵포털 담당자 알려줘");

        assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
        assertThat(result.answer()).contains("김민수").contains("SRE플랫폼팀");
        assertThat(result.lookups()).hasSize(1);
        assertThat(result.lookups().get(0).result().rowCount()).isEqualTo(1);
        assertThat(result.trace()).extracting(TraceStep::stage)
                .contains(Stage.QUERY_UNDERSTANDING, Stage.RAG_CACHE_SEARCH, Stage.REUSE_DECISION,
                        Stage.SQL_PREPARATION, Stage.SQL_VALIDATE_EXECUTE, Stage.ANSWER_GENERATION);
    }

    @Test
    @DisplayName("같은 질의를 반복하면 검증 SQL 캐시를 재사용한다")
    void reusesVerifiedSql() {
        flowService.handle("u1", "WEB", "s-cache-1", "APP-1003 배포관리콘솔 관련 Jira 작업이력 보여줘");
        PipelineResult second = flowService.handle("u1", "WEB", "s-cache-2",
                "APP-1003 배포관리콘솔 관련 Jira 작업이력 보여줘");

        assertThat(second.cacheHit()).isTrue();
        assertThat(second.answer()).contains("INC-1051");
    }

    @Test
    @DisplayName("멀티턴 맥락으로 APP ID 를 이어받아 추가 조회를 처리한다")
    void keepsMultiTurnContext() {
        flowService.handle("u1", "WEB", "s-multi", "APP-1002 장애지원에이전트 담당자 알려줘");
        PipelineResult followUp = flowService.handle("u1", "WEB", "s-multi", "그 앱 사용자는 몇 명이야?");

        assertThat(followUp.plan().appIds()).contains("APP-1002");
        assertThat(followUp.answer()).contains("4명");
    }

    @Test
    @DisplayName("실행 이력과 LLM 호출 이력이 runId 로 저장된다")
    void persistsAuditTrail() {
        PipelineResult result = flowService.handle("u1", "MESSENGER", "s-audit",
                "SRE플랫폼팀 부서장은 누구인가");

        List<java.util.Map<String, Object>> run = auditRepository.findRun(result.runId());
        List<java.util.Map<String, Object>> steps = auditRepository.findSteps(result.runId());

        assertThat(run).hasSize(1);
        assertThat(run.get(0).get("STATUS")).isEqualTo("SUCCESS");
        assertThat(steps).hasSizeGreaterThanOrEqualTo(6);
    }
}
