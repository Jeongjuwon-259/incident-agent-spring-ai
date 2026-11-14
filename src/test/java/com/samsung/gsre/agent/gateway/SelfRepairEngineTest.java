package com.samsung.gsre.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.llm.gateway.HeuristicLlmGateway;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.Intent;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.SqlOrigin;
import com.samsung.gsre.agent.state.IncidentQueryFlowService;
import com.samsung.gsre.agent.state.PipelineResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Self-Repair 루프 통합 테스트: 첫 SQL 이 실행 오류를 내고, 오류 피드백으로 고친 SQL 이 성공한다. */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "agent.rag.similarity-threshold=0.99",
        "spring.datasource.url=jdbc:h2:mem:repairtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@Import(SelfRepairEngineTest.RepairTestConfig.class)
class SelfRepairEngineTest {

    @Autowired
    private IncidentQueryFlowService flowService;

    @Test
    @DisplayName("실행 오류 → SQL 재생성 → 재실행 성공까지 추적된다")
    void repairsFailingSql() {
        PipelineResult result = flowService.handle("u1", "WEB", "s-repair",
                "APP-1006 권한신청시스템 담당자 알려줘");

        LookupResult lookup = result.lookups().get(0);
        assertThat(lookup.failed()).isFalse();
        assertThat(lookup.repairNotes()).isNotEmpty();
        assertThat(lookup.repairNotes().get(0)).contains("1차 오류");
        assertThat(lookup.result().rowCount()).isEqualTo(1);
        assertThat(lookup.sql()).doesNotContain("not_a_column");
        assertThat(result.answer()).contains("최하늘");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepairTestConfig {

        @Bean
        @Primary
        LlmGateway repairingGateway(HeuristicLlmGateway delegate) {
            return new RepairingGateway(delegate);
        }
    }

    /** 첫 생성만 일부러 깨진 SQL 을 주고, 재생성은 정상 템플릿으로 돌려주는 테스트 더블. */
    static class RepairingGateway implements LlmGateway {

        private final HeuristicLlmGateway delegate;
        private final AtomicInteger generations = new AtomicInteger();

        RepairingGateway(HeuristicLlmGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public String provider() {
            return "test-repairing-gateway";
        }

        @Override
        public QueryPlan understand(String question, List<ConversationTurn> history) {
            return delegate.understand(question, history);
        }

        @Override
        public ReuseDecision judgeReuse(String question, QueryPlan plan, LookupTask task,
                                        List<CacheCandidate> candidates) {
            return ReuseDecision.reject("테스트: 캐시를 사용하지 않습니다.");
        }

        @Override
        public PreparedSql generateSql(String question, QueryPlan plan, LookupTask task,
                                       List<CacheCandidate> similar) {
            if (task.intent() == Intent.CONTACT && generations.getAndIncrement() == 0) {
                return new PreparedSql("SELECT a.not_a_column FROM svcm_tb_app_master a "
                        + "WHERE a.app_id = :appId LIMIT 10",
                        Map.of("appId", task.appId()), List.of("svcm_tb_app_master"),
                        SqlOrigin.GENERATED, null, "테스트용 오류 SQL");
            }
            return delegate.generateSql(question, plan, task, similar);
        }

        @Override
        public PreparedSql repairSql(String question, LookupTask task, PreparedSql failed, String errorMessage,
                                     List<CacheCandidate> similar) {
            return delegate.generateSql(question, null, task, similar);
        }

        @Override
        public AnswerOutcome composeAnswer(String question, List<LookupTask> plannedTasks,
                                           List<LookupResult> results, List<ConversationTurn> history) {
            return delegate.composeAnswer(question, plannedTasks, results, history);
        }

        @Override
        public JudgeVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
            return delegate.judgeAnswer(question, expectedAnswer, actualAnswer);
        }

        @Override
        public JudgeVerdict judgeFaithfulness(String question, String resultContext, String answer) {
            return delegate.judgeFaithfulness(question, resultContext, answer);
        }
    }
}
