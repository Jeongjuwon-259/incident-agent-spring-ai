package com.samsung.gsre.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.gsre.agent.state.AgentQueryCommand;
import com.samsung.gsre.agent.state.IncidentQueryFlowService;
import com.samsung.gsre.agent.state.PipelineResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class ContextSmokeTest {

    @Autowired
    private IncidentQueryFlowService flowService;

    @Test
    @DisplayName("스프링 컨텍스트가 기동되고 6단계 파이프라인이 오프라인 모드로 답변을 만든다")
    void contextLoadsAndAnswers() {
        PipelineResult result = flowService.handle(new AgentQueryCommand("tester", "WEB", "smoke-session",
                "APP-1001 서비스맵포털 담당자 알려줘"));

        assertThat(result.runId()).startsWith("RUN-");
        assertThat(result.answer()).contains("김민수");
        assertThat(result.trace()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(result.lookups()).isNotEmpty();
    }
}
