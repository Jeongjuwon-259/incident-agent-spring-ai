package com.samsung.gsre.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MCP 확장 테이블 + 양방향 Reshaping 엔진 검증.
 *
 * <p>세 층을 한 클래스에서 확인한다.</p>
 * <ol>
 *   <li>룰 엔진 단위: Raw K8s 응답 압축, INBOUND 로우 → tools/call 변환, 필수 필드/문법 오류.</li>
 *   <li>오케스트레이터 통합: 실제 H2 시드 + 감사 로그 1행(raw/reshaped) 기록.</li>
 *   <li>HTTP 계약: 등록/조회/실행/404/검증 오류.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "agent.mcp.transport-mode=OFFLINE",
        "spring.datasource.url=jdbc:h2:mem:mcptest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class McpReshapingFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataReshaper reshaper;

    @Autowired
    private McpClientGateway gateway;

    @Autowired
    private McpFixtureCatalog fixtures;

    @Autowired
    private McpMasterRepository masterRepository;

    @Autowired
    private McpPayloadLogRepository logRepository;

    @Autowired
    private McpOrchestratorService orchestrator;

    // ==================================================================
    // 1. 룰 엔진 (단위)
    // ==================================================================

    @Test
    @DisplayName("OUTBOUND 룰이 방대한 K8s Raw 응답을 Pod 6개 필드로 압축하고 Succeeded 는 버린다")
    void outboundRuleCompressesK8sRawResponse() {
        McpApiMaster api = masterRepository.findActive("MCP-K8S-001").orElseThrow();
        McpCallResult call = gateway.call(api, objectMapper.createObjectNode());

        ReshapingResult shaped = reshaper.reshape(api.responseReshapingRule(), call.payload(), api.toolName());
        JsonNode pods = shaped.payload();

        assertThat(pods.isArray()).isTrue();
        assertThat(pods.size()).isEqualTo(3);
        assertThat(shaped.sourceCount()).isEqualTo(4);
        assertThat(shaped.droppedCount()).isEqualTo(1);

        JsonNode running = pods.get(0);
        assertThat(running.path("podName").asText()).isEqualTo("svcm-portal-7d9f8c6b5-2xk4p");
        assertThat(running.path("namespace").asText()).isEqualTo("svcm-portal");
        assertThat(running.path("phase").asText()).isEqualTo("Running");
        assertThat(running.path("node").asText()).isEqualTo("macmini-node-01");
        // 컨테이너 2개 restartCount 2 + 1 → 한 필드로 집계
        assertThat(running.path("restarts").asInt()).isEqualTo(3);
        assertThat(running.path("image").asText()).isEqualTo("registry.local/svcm/portal:2.5.1");

        JsonNode failed = pods.get(1);
        assertThat(failed.path("phase").asText()).isEqualTo("Failed");
        assertThat(failed.path("restarts").asInt()).isEqualTo(7);

        JsonNode pending = pods.get(2);
        assertThat(pending.path("phase").asText()).isEqualTo("Pending");
        assertThat(pending.path("node").asText()).isEqualTo("unassigned");
        assertThat(pending.path("restarts").asInt()).isZero();

        // 압축 효과: Raw 응답보다 결과가 훨씬 작다
        assertThat(call.rawBody().length()).isGreaterThan(pods.toString().length() * 3);
        assertThat(shaped.warnings()).isEmpty();
    }

    @Test
    @DisplayName("INBOUND 룰이 내부 DB 로우를 MCP tools/call 규격(entries[])으로 변환한다")
    void inboundRuleBuildsToolCallPayload() {
        McpApiMaster api = masterRepository.findActive("MCP-JIRA-001").orElseThrow();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("issue_key", "INC-1042");
        row.put("project_key", "SVCM");
        row.put("app_name", "배포관리콘솔");
        row.put("assignee_name", "이서연");
        row.put("worklog_hours", new java.math.BigDecimal("3.0"));
        row.put("worklog_date", "2026-11-07");
        row.put("summary", "  배포 실패 대응  ");

        ReshapingResult shaped = reshaper.reshape(api.requestReshapingRule(), List.of(row), api.toolName());
        JsonNode payload = shaped.payload();

        assertThat(payload.path("tool").asText()).isEqualTo("jira.worklog.sync");
        JsonNode entries = payload.path("arguments").path("entries");
        assertThat(entries.size()).isEqualTo(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.path("issueKey").asText()).isEqualTo("INC-1042");
        assertThat(entry.path("appName").asText()).isEqualTo("배포관리콘솔");
        assertThat(entry.path("hours").isNumber()).isTrue();
        assertThat(entry.path("hours").decimalValue()).isEqualByComparingTo("3");
        assertThat(entry.path("workDate").asText()).isEqualTo("2026-11-07");
        assertThat(entry.path("summary").asText()).isEqualTo("배포 실패 대응");
    }

    @Test
    @DisplayName("필수 필드의 원천이 없으면 필드명과 경로를 알려주며 실패한다")
    void requiredFieldMissingFails() {
        String rule = """
                {"mode":"COLLECTION","itemPath":"$[*]",
                 "fields":[{"target":"podName","source":"$.metadata.name","required":true}]}
                """;
        List<Map<String, Object>> rows = List.of(Map.of("kind", "Pod"));

        assertThatThrownBy(() -> reshaper.reshape(rule, rows, "k8s.pod.list"))
                .isInstanceOf(ReshapingException.class)
                .hasMessageContaining("필수 필드")
                .hasMessageContaining("podName")
                .hasMessageContaining("$.metadata.name");
    }

    @Test
    @DisplayName("깨진 룰 JSON 은 실행 전에 400 성격의 오류로 거부된다")
    void malformedRuleIsRejected() {
        assertThatThrownBy(() -> reshaper.parse("{ this is not json }"))
                .isInstanceOf(ReshapingException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("룰이 없으면 원본 payload 를 그대로 쓰고 경고를 남긴다")
    void blankRuleKeepsRawPayload() {
        ReshapingResult shaped = reshaper.reshape("", Map.of("a", 1), "any.tool");

        assertThat(shaped.payload().path("a").asInt()).isEqualTo(1);
        assertThat(shaped.warnings()).hasSize(1);
    }

    // ==================================================================
    // 2. 오케스트레이터 (통합: H2 시드 + 감사 로그)
    // ==================================================================

    @Test
    @DisplayName("OUTBOUND 실행: 픽스처로 폴백하고 raw/reshaped 를 감사 로그 1행에 남긴다")
    void outboundExecutionWritesAuditLog() {
        McpExecutionResult result = orchestrator.execute("MCP-K8S-001",
                Map.of("namespace", "svcm-portal"), "RUN-MCP-OUT-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.FALLBACK);
        assertThat(result.logId()).isPositive();
        assertThat(result.request().path("namespace").asText()).isEqualTo("svcm-portal");
        assertThat(result.response().size()).isEqualTo(3);
        assertThat(result.stages()).anyMatch(s -> s.startsWith("1 기준정보"))
                .anyMatch(s -> s.startsWith("2 요청 Reshaping"))
                .anyMatch(s -> s.startsWith("3 통신"))
                .anyMatch(s -> s.startsWith("4 응답 Reshaping"))
                .anyMatch(s -> s.startsWith("5 감사로그"));

        List<McpPayloadLog> logs = logRepository.findByCallId(result.callId());
        assertThat(logs).hasSize(1);
        McpPayloadLog log = logs.get(0);
        assertThat(log.apiId()).isEqualTo("MCP-K8S-001");
        assertThat(log.direction()).isEqualTo("OUTBOUND");
        assertThat(log.runId()).isEqualTo("RUN-MCP-OUT-1");
        assertThat(log.statusCode()).isZero();
        assertThat(log.rawPayloadOut()).contains("PodList");
        assertThat(log.reshapedPayloadOut()).contains("podName");
        assertThat(log.rawPayloadOut().length()).isGreaterThan(log.reshapedPayloadOut().length());
        assertThat(log.errorMessage()).contains("OFFLINE");
    }

    @Test
    @DisplayName("INBOUND 실행: Jira 뷰 로우를 읽어 entries 로 변환하고 접수 결과만 남긴다")
    void inboundExecutionReadsRowsAndSendsToolCall() {
        McpExecutionResult result = orchestrator.execute("MCP-JIRA-001", Map.of(), "RUN-MCP-IN-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.FALLBACK);
        assertThat(result.request().path("tool").asText()).isEqualTo("jira.worklog.sync");
        JsonNode entries = result.request().path("arguments").path("entries");
        assertThat(entries.size()).isEqualTo(6);
        assertThat(entries.get(0).path("issueKey").asText()).isEqualTo("INC-1055");
        assertThat(entries.get(0).path("workDate").asText()).isEqualTo("2026-11-12");
        assertThat(entries.get(0).path("hours").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(result.response().path("synced").asInt()).isEqualTo(3);

        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.direction()).isEqualTo("INBOUND");
        assertThat(log.rawPayloadIn()).contains("issue_key");
        assertThat(log.reshapedPayloadIn()).contains("\"entries\"");
        assertThat(log.reshapedPayloadOut()).contains("receiptId");
    }

    @Test
    @DisplayName("BIDIRECTIONAL 실행: 내부 자산 로우를 알람 스펙으로 변환 발송하고 응답에서 접수 결과를 정제한다")
    void bidirectionalExecutionShapesBothWays() {
        McpExecutionResult result = orchestrator.execute("MCP-ALERT-001", Map.of(), null, "tester");

        JsonNode alerts = result.request().path("arguments").path("alerts");
        assertThat(alerts.size()).isEqualTo(2);
        assertThat(alerts.get(0).path("severity").asText()).isEqualTo("HIGH");
        assertThat(alerts.get(0).path("appId").asText()).isEqualTo("APP-1001");
        assertThat(result.response().path("accepted").asInt()).isEqualTo(2);
        assertThat(result.response().path("channel").asText()).isEqualTo("#sre-alerts");

        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.direction()).isEqualTo("BIDIRECTIONAL");
        assertThat(log.rawPayloadIn()).contains("APP-1002");
        assertThat(log.rawPayloadOut()).contains("ALT-260113-7781");
    }

    // ==================================================================
    // 3. HTTP 계약
    // ==================================================================

    @Test
    @DisplayName("등록된 MCP API 3건이 조회된다")
    void listsRegisteredApis() throws Exception {
        mockMvc.perform(get("/api/v1/mcp/apis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-K8S-001')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-JIRA-001')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-ALERT-001')]").exists());
    }

    @Test
    @DisplayName("새 MCP API 를 등록하고 즉시 실행할 수 있다 (코드 배포 없이 확장)")
    void registerAndExecuteCustomApi() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiId", "MCP-TEST-API");
        body.put("apiName", "테스트 도구");
        body.put("toolName", "test.echo");
        body.put("direction", "OUTBOUND");
        body.put("endpointUrl", "http://localhost:18081/mcp");
        body.put("responseReshapingRule", """
                {"mode":"OBJECT","fields":[
                  {"target":"fixtureMode","source":"$.mode"},
                  {"target":"toolName","source":"$.tool"},
                  {"target":"missing","source":"$.nope","default":"none"}]}
                """);
        body.put("createdBy", "tester");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiId").value("MCP-TEST-API"))
                .andExpect(jsonPath("$.direction").value("OUTBOUND"));

        mockMvc.perform(post("/api/v1/mcp/execute/MCP-TEST-API")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"q\":\"svcm\"},\"runId\":\"RUN-MCP-API-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.response.fixtureMode").value("OFFLINE_FIXTURE"))
                .andExpect(jsonPath("$.response.toolName").value("test.echo"))
                .andExpect(jsonPath("$.response.missing").value("none"))
                .andExpect(jsonPath("$.logId").isNumber());
    }

    @Test
    @DisplayName("미등록 API 실행은 404 MCP_API_NOT_FOUND")
    void unknownApiReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/execute/MCP-NOT-EXIST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MCP_API_NOT_FOUND"));
    }

    @Test
    @DisplayName("지원하지 않는 direction 등록은 400")
    void invalidDirectionIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "MCP-BAD-DIR",
                "apiName", "잘못된 방향",
                "toolName", "bad.tool",
                "direction", "SIDEWAYS",
                "endpointUrl", "http://localhost:18080/mcp");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("INBOUND 인데 source_query 가 없으면 등록을 거부한다")
    void inboundWithoutSourceQueryIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "MCP-BAD-IN",
                "apiName", "원천 없는 인바운드",
                "toolName", "bad.inbound",
                "direction", "INBOUND",
                "endpointUrl", "http://localhost:18080/mcp");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("source_query")));
    }

    @Test
    @DisplayName("감사 로그 조회는 raw 와 reshaped payload 를 함께 돌려준다")
    void logsExposeRawAndReshaped() throws Exception {
        McpExecutionResult result = orchestrator.execute("MCP-K8S-001", Map.of(), "RUN-MCP-LOG-1", "tester");

        mockMvc.perform(get("/api/v1/mcp/logs").param("apiId", "MCP-K8S-001").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callId").value(result.callId()))
                .andExpect(jsonPath("$[0].rawPayloadOut").value(org.hamcrest.Matchers.containsString("PodList")))
                .andExpect(jsonPath("$[0].reshapedPayloadOut").value(org.hamcrest.Matchers.containsString("podName")));
    }

    @Test
    @DisplayName("오프라인 픽스처는 같은 입력에 항상 같은 Raw 를 돌려준다")
    void offlineFixtureIsDeterministic() {
        McpApiMaster api = masterRepository.findActive("MCP-K8S-001").orElseThrow();

        McpCallResult first = gateway.call(api, objectMapper.createObjectNode());
        McpCallResult second = gateway.call(api, objectMapper.createObjectNode());

        assertThat(first.fallback()).isTrue();
        assertThat(first.statusCode()).isZero();
        assertThat(first.rawBody()).isEqualTo(second.rawBody());
        assertThat(first.payload()).isEqualTo(second.payload());
    }

    @Test
    @DisplayName("HTTP 모드: 실제 tools/call 을 보내고 SSE 응답에서 도구 결과를 꺼내 Reshaping 한다")
    void httpGatewayCallsMcpServerAndUnwrapsSse() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"{\\\"value\\\":42,\\\"name\\\":\\\"svcm\\\"}\"}]}}\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AgentProperties props = new AgentProperties();
            props.getMcp().setTransportMode("HTTP");
            McpClientGateway httpGateway = new McpClientGateway(objectMapper, props, fixtures);
            McpApiMaster api = new McpApiMaster("MCP-HTTP-TEST", "HTTP 테스트", "k8s.pod.list",
                    McpDirection.OUTBOUND, "HTTP", "http://127.0.0.1:" + port + "/mcp", "NONE", null,
                    null, null, "{\"mode\":\"OBJECT\",\"fields\":[{\"target\":\"value\",\"source\":\"$.value\"},"
                            + "{\"target\":\"name\",\"source\":\"$.name\"}]}",
                    null, 2_000, true, null, "tester", Instant.now(), Instant.now());

            McpCallResult call = httpGateway.call(api, objectMapper.createObjectNode().put("q", "x"));

            assertThat(call.fallback()).isFalse();
            assertThat(call.statusCode()).isEqualTo(200);
            assertThat(call.payload().path("value").asInt()).isEqualTo(42);
            assertThat(received.get()).contains("\"method\":\"tools/call\"")
                    .contains("\"name\":\"k8s.pod.list\"")
                    .contains("\"q\":\"x\"");

            ReshapingResult shaped = reshaper.reshape(api.responseReshapingRule(), call.payload(), api.toolName());
            assertThat(shaped.payload().path("value").asInt()).isEqualTo(42);
            assertThat(shaped.payload().path("name").asText()).isEqualTo("svcm");
        } finally {
            server.stop(0);
        }
    }
}
