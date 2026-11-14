package com.samsung.gsre.agent.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.llm.exception.LlmException;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.RunStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 로컬 LLM 연결 실패 시 사용자에게 실행 가능한 안내를 주고 FAILED 로 끝나는지 검증한다. */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:llmfail;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@Import(LlmUnavailableFlowTest.FailingConfig.class)
class LlmUnavailableFlowTest {

    @Autowired
    private IncidentQueryFlowService flowService;

    @Test
    @DisplayName("LLM 연결 실패는 SUCCESS 로 덮이지 않고 FAILED + 조치 안내로 끝난다")
    void failsWithGuidance() {
        PipelineResult result = flowService.handle("u1", "WEB", "s-llm-fail",
                "APP-1001 서비스맵포털 담당자 알려줘");

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.answer()).contains("로컬 LLM").contains("LOCAL_LLM_BASE_URL");
        assertThat(result.errorMessage()).isNotBlank();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingConfig {

        @Bean
        @Primary
        LlmGateway failingGateway() {
            return new FailingGateway();
        }
    }

    /** 모든 호출이 연결 실패로 끝나는 게이트웨이. */
    static class FailingGateway implements LlmGateway {

        private LlmException failure() {
            return new LlmException.LlmUnavailableException(
                    "로컬 LLM 엔드포인트에 연결할 수 없습니다. (Connection refused)",
                    new java.net.ConnectException("Connection refused"));
        }

        @Override
        public String provider() {
            return "test-failing-gateway";
        }

        @Override
        public QueryPlan understand(String question, List<ConversationTurn> history) {
            throw failure();
        }

        @Override
        public ReuseDecision judgeReuse(String question, QueryPlan plan, LookupTask task,
                                        List<CacheCandidate> candidates) {
            throw failure();
        }

        @Override
        public PreparedSql generateSql(String question, QueryPlan plan, LookupTask task,
                                       List<CacheCandidate> similar) {
            throw failure();
        }

        @Override
        public PreparedSql repairSql(String question, LookupTask task, PreparedSql failed, String errorMessage,
                                     List<CacheCandidate> similar) {
            throw failure();
        }

        @Override
        public AnswerOutcome composeAnswer(String question, List<LookupTask> plannedTasks,
                                           List<LookupResult> results, List<ConversationTurn> history) {
            throw failure();
        }

        @Override
        public JudgeVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
            throw failure();
        }

        @Override
        public JudgeVerdict judgeFaithfulness(String question, String resultContext, String answer) {
            throw failure();
        }
    }
}
