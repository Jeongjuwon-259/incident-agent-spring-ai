package com.samsung.gsre.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 사용자 API 계약 테스트. */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:apitest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AgentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("질의 API 가 답변과 실행 추적을 반환한다")
    void queryReturnsAnswer() throws Exception {
        mockMvc.perform(post("/api/v1/agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "u1", "channel", "WEB", "sessionId", "api-session",
                                "question", "APP-1001 서비스맵포털 담당자 알려줘"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.startsWith("RUN-")))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("김민수")))
                .andExpect(jsonPath("$.lookups.length()").value(1))
                .andExpect(jsonPath("$.trace.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));
    }

    @Test
    @DisplayName("질의가 비어 있으면 400 과 필드 오류를 돌려준다")
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("검증자 없는 캐시 등록은 거부한다")
    void rejectsCacheRegistrationWithoutVerifier() throws Exception {
        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "테스트 질의",
                                "sql", "SELECT app_id FROM svcm_tb_app_master LIMIT 10"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.verifiedBy").exists());
    }

    @Test
    @DisplayName("위험한 SQL 은 캐시에 등록되지 않는다")
    void rejectsUnsafeSql() throws Exception {
        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "삭제 질의",
                                "sql", "DELETE FROM svcm_tb_app_master",
                                "verifiedBy", "tester"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("평가 테스트 케이스 목록을 조회할 수 있다")
    void listsEvalCases() throws Exception {
        mockMvc.perform(get("/api/v1/eval/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));
    }

    @Test
    @DisplayName("평가 배치를 접수하면 202 와 Batch ID 를 돌려준다")
    void acceptsEvalBatch() throws Exception {
        mockMvc.perform(post("/api/v1/eval/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tcIds", java.util.List.of("TC-005"), "all", false))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value(org.hamcrest.Matchers.startsWith("EVAL-")))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }
}
