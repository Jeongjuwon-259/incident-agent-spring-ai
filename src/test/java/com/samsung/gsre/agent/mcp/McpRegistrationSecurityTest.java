package com.samsung.gsre.agent.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 테이블 기반 MCP/REST 등록·실행의 보안 경계 검증.
 *
 * <p>등록 시점: 시크릿 헤더·평문 auth 값·메타데이터 주소·미지원 전송 방식을 거부한다.
 * 실행 시점: 요청 payload 의 토큰이 API 응답/감사 로그에 평문으로 남지 않는다.</p>
 */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "agent.mcp.transport-mode=OFFLINE",
        "spring.datasource.url=jdbc:h2:mem:mcpsec;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class McpRegistrationSecurityTest {

    private static final String SECRET = "sk-live-SECRET-9f8e7d";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("headers_config 에 인증 헤더를 넣으면 등록을 거부한다")
    void rejectsSecretHeader() throws Exception {
        Map<String, Object> body = base("SEC-HEADER-001");
        body.put("headersConfig", "{\"Authorization\":\"Bearer " + SECRET + "\"}");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("headersConfig")));
    }

    @Test
    @DisplayName("auth_config 에 시크릿 값을 직접 넣으면 등록을 거부한다")
    void rejectsLiteralSecretInAuthConfig() throws Exception {
        Map<String, Object> body = base("SEC-AUTH-001");
        body.put("authType", "BEARER");
        body.put("authConfig", "{\"envVar\":\"MCP_TOKEN\",\"value\":\"" + SECRET + "\"}");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("시크릿")));
    }

    @Test
    @DisplayName("클라우드 메타데이터 주소는 SSRF 정책으로 등록을 거부한다")
    void rejectsMetadataEndpoint() throws Exception {
        Map<String, Object> body = base("SEC-SSRF-001");
        body.put("endpointUrl", "http://169.254.169.254/latest/meta-data");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTBOUND_TARGET_BLOCKED"));
    }

    @Test
    @DisplayName("지원하지 않는 transport_type 은 등록을 거부한다")
    void rejectsUnknownTransport() throws Exception {
        Map<String, Object> body = base("SEC-TRANSPORT-001");
        body.put("transportType", "GRPC");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("transport_type")));
    }

    @Test
    @DisplayName("실행 payload 의 토큰은 API 응답과 감사 로그에 평문으로 남지 않는다")
    void masksSecretInExecutionAndLog() throws Exception {
        Map<String, Object> body = base("SEC-LOG-001");
        body.put("requestReshapingRule", """
                {"version":"1.0","mode":"OBJECT","fields":[
                  {"target":"token","source":"$.token"},
                  {"target":"appId","source":"$.appId","default":"APP-1001"}]}
                """);

        MvcResult registered = mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String registeredBody = registered.getResponse().getContentAsString();
        if (registeredBody.contains(SECRET)) {
            throw new AssertionError("등록 응답에 시크릿이 노출되었습니다: " + registeredBody);
        }

        MvcResult executed = mockMvc.perform(post("/api/v1/mcp/execute/SEC-LOG-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"appId\":\"APP-1001\",\"token\":\"" + SECRET + "\"},"
                                + "\"requestedBy\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.token").value("***"))
                .andReturn();
        if (executed.getResponse().getContentAsString().contains(SECRET)) {
            throw new AssertionError("실행 응답에 시크릿이 노출되었습니다.");
        }

        MvcResult logs = mockMvc.perform(get("/api/v1/mcp/logs").param("apiId", "SEC-LOG-001"))
                .andExpect(status().isOk())
                .andReturn();
        String logBody = logs.getResponse().getContentAsString();
        if (logBody.contains(SECRET)) {
            throw new AssertionError("감사 로그 응답에 시크릿이 노출되었습니다: " + logBody);
        }
    }

    /** 등록 최소 필드. 테스트마다 apiId 만 다르게 쓴다. */
    private Map<String, Object> base(String apiId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiId", apiId);
        body.put("apiName", "보안 테스트 API");
        body.put("toolName", "security.test");
        body.put("direction", "OUTBOUND");
        body.put("transportType", "REST");
        body.put("endpointUrl", "http://127.0.0.1:18080/api/security-test");
        body.put("httpMethod", "POST");
        body.put("authType", "NONE");
        body.put("createdBy", "tester");
        return body;
    }
}
