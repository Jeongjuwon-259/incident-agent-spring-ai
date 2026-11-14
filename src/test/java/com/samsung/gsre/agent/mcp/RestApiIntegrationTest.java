package com.samsung.gsre.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.gsre.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 테이블 기반 REST API 확장 검증. MCP 와 <b>같은 테이블·같은 룰 엔진</b>으로 순수 REST 를 구동한다.
 *
 * <p>이 클래스는 실제 통신으로 확인한다. embedded {@link HttpServer} 를 시드에 적힌 포트(18080)에 띄우고
 * {@code agent.mcp.transport-mode=HTTP} 로 돌려, 다음을 서버 쪽에서 직접 관찰한다.</p>
 * <ol>
 *   <li>{@code GET} + 경로변수 치환(URL 인코딩) + 남은 파라미터 Query String + headers_config 헤더</li>
 *   <li>{@code POST} 본문이 JSON-RPC 봉투 없는 순수 REST JSON({@code source, incidents[]})인지</li>
 *   <li>{@code BIDIRECTIONAL} webhook: 내부 조회 → 발송 → 응답 정제</li>
 *   <li>감사 로그에 실제 transport_type / http_method 가 남는지</li>
 *   <li>HTTP 5xx 를 숨기지 않는지, 오프라인 픽스처가 결정적인지</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "agent.mcp.transport-mode=HTTP",
        "spring.datasource.url=jdbc:h2:mem:restapitest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class RestApiIntegrationTest {

    private static final int PORT = 18080;

    private static HttpServer server;
    private static final AtomicReference<Captured> CMDB = new AtomicReference<>();
    private static final AtomicReference<Captured> INCIDENT_SYNC = new AtomicReference<>();
    private static final AtomicReference<Captured> WEBHOOK = new AtomicReference<>();
    private static final AtomicReference<Captured> ECHO_PUT = new AtomicReference<>();
    private static final AtomicReference<Captured> ECHO_DELETE = new AtomicReference<>();
    private static final AtomicReference<Captured> ECHO_PATCH = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataReshaper reshaper;

    @Autowired
    private McpFixtureCatalog fixtures;

    @Autowired
    private McpMasterRepository masterRepository;

    @Autowired
    private McpPayloadLogRepository logRepository;

    @Autowired
    private McpOrchestratorService orchestrator;

    // ==================================================================
    // 실제 REST 서버 (시드의 endpoint_url 과 같은 포트)
    // ==================================================================

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/cmdb/apps", exchange -> {
            CMDB.set(Captured.of(exchange));
            respond(exchange, 200, CMDB_BODY);
        });
        server.createContext("/api/incidents/sync", exchange -> {
            INCIDENT_SYNC.set(Captured.of(exchange));
            respond(exchange, 200, """
                    {"syncedCount": 5, "batchId": "INC-BATCH-261114-0001", "status": "ACCEPTED",
                     "processedAt": "2026-11-14T09:12:41+09:00", "server": "incident-rest-live"}
                    """);
        });
        server.createContext("/api/v1/webhooks/sre-alert", exchange -> {
            WEBHOOK.set(Captured.of(exchange));
            respond(exchange, 200, """
                    {"notified": 2, "channel": "#sre-alerts", "status": "SENT",
                     "receiptId": "WH-261114-4412", "server": "webhook-rest-live"}
                    """);
        });
        server.createContext("/api/echo/put", exchange -> {
            ECHO_PUT.set(Captured.of(exchange));
            respond(exchange, 200, "{\"method\":\"PUT\",\"echoed\":true}");
        });
        server.createContext("/api/echo/delete", exchange -> {
            ECHO_DELETE.set(Captured.of(exchange));
            respond(exchange, 200, "{\"deleted\":true,\"ticketId\":\"INC-1055\"}");
        });
        server.createContext("/api/echo/patch", exchange -> {
            ECHO_PATCH.set(Captured.of(exchange));
            respond(exchange, 200, "{\"patched\":true,\"method\":\"PATCH\"}");
        });
        server.createContext("/api/fail", exchange -> {
            Captured.of(exchange);
            respond(exchange, 500, "{\"error\":\"cmdb backend timeout\"}");
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================================================================
    // 1. 기준정보: 한 테이블에 MCP 3건 + REST 3건이 함께 산다
    // ==================================================================

    @Test
    @DisplayName("등록 테이블 하나에 MCP 시드 3건과 REST 시드 3건이 함께 조회된다")
    void seedsExposeBothProtocols() throws Exception {
        mockMvc.perform(get("/api/v1/mcp/apis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-K8S-001' && @.transportType == 'HTTP')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-JIRA-001')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'MCP-ALERT-001')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'REST-CMDB-001' && @.transportType == 'REST'"
                        + " && @.httpMethod == 'GET')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'REST-INCIDENT-001' && @.httpMethod == 'POST')]").exists())
                .andExpect(jsonPath("$[?(@.apiId == 'REST-NOTIFY-001' && @.direction == 'BIDIRECTIONAL')]").exists());
    }

    @Test
    @DisplayName("MCP 시드는 http_method 기본값 POST 이고 REST 시드는 headers_config 를 갖는다")
    void seedsCarryTransportDetails() {
        McpApiMaster k8s = masterRepository.findActive("MCP-K8S-001").orElseThrow();
        assertThat(k8s.isRestTransport()).isFalse();
        assertThat(k8s.httpMethod()).isEqualTo("POST");

        McpApiMaster cmdb = masterRepository.findActive("REST-CMDB-001").orElseThrow();
        assertThat(cmdb.isRestTransport()).isTrue();
        assertThat(cmdb.httpMethodValue()).isEqualTo(McpHttpMethod.GET);
        assertThat(cmdb.headersConfig()).contains("X-GSRE-Source");
    }

    // ==================================================================
    // 2. GET: 경로변수 치환 + Query String + 커스텀 헤더 (실통신)
    // ==================================================================

    @Test
    @DisplayName("GET 실행: {appId} 가 인코딩 치환되고 남은 파라미터가 Query String 으로 나가며 응답이 정제된다")
    void restGetSubstitutesPathVariableAndBuildsQuery() {
        McpExecutionResult result = orchestrator.execute("REST-CMDB-001",
                Map.of("appId", "APP 1003"), "RUN-REST-GET-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(result.transportType()).isEqualTo("REST");
        assertThat(result.httpMethod()).isEqualTo("GET");
        assertThat(result.statusCode()).isEqualTo(200);

        Captured request = CMDB.get();
        assertThat(request).isNotNull();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/api/cmdb/apps/APP%201003"); // 공백 → %20, 경로 세그먼트 인코딩
        assertThat(request.query()).contains("include=owners%2Cdeps%2Cmetrics").contains("depth=2")
                .doesNotContain("appId=");                                  // 경로변수는 Query 에서 빠진다
        assertThat(request.body()).isEmpty();                                // GET 은 본문 없음
        assertThat(request.header("X-GSRE-Source")).isEqualTo("incident-agent-spring-ai");
        assertThat(request.header("Content-Type")).isNull();                 // 본문이 없으면 Content-Type 도 없다

        JsonNode shaped = result.response();
        assertThat(shaped.path("appId").asText()).isEqualTo("APP-1001");
        assertThat(shaped.path("appName").asText()).isEqualTo("서비스맵포털");
        assertThat(shaped.path("owner").asText()).isEqualTo("김민수");
        assertThat(shaped.path("ownerDept").asText()).isEqualTo("SRE플랫폼팀");
        assertThat(shaped.path("tier").asText()).isEqualTo("TIER-1");
        assertThat(shaped.path("openIncidents").asInt()).isEqualTo(1);
        assertThat(shaped.path("auditLog").isMissingNode()).isTrue();        // 노이즈는 버려진다

        // 감사 로그: raw 는 원본 그대로, reshaped 는 압축본. 프로토콜/메서드도 남는다.
        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.transportType()).isEqualTo("REST");
        assertThat(log.httpMethod()).isEqualTo("GET");
        assertThat(log.rawPayloadOut()).contains("auditLog").contains("cmdb-live-test");
        assertThat(log.reshapedPayloadOut()).contains("\"owner\"").doesNotContain("auditLog");
        assertThat(log.rawPayloadOut().length()).isGreaterThan(log.reshapedPayloadOut().length());
    }

    // ==================================================================
    // 3. POST INBOUND: JSON-RPC 봉투 없는 순수 REST JSON Body
    // ==================================================================

    @Test
    @DisplayName("INBOUND POST 실행: 내부 장애 이력이 JSON-RPC 봉투 없는 REST JSON(source, incidents[])으로 전송된다")
    void restPostSendsPureRestJsonBody() {
        McpExecutionResult result = orchestrator.execute("REST-INCIDENT-001", Map.of(), "RUN-REST-POST-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(result.httpMethod()).isEqualTo("POST");
        assertThat(result.statusCode()).isEqualTo(200);

        Captured request = INCIDENT_SYNC.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("Content-Type")).startsWith("application/json");
        assertThat(request.header("X-Sync-Mode")).isEqualTo("batch");
        assertThat(request.body()).doesNotContain("jsonrpc").doesNotContain("tools/call");
        assertThat(request.body()).contains("\"source\":\"gsre-agent\"")
                .contains("\"schemaVersion\":\"1.0\"")
                .contains("\"incidents\":[");

        JsonNode sent = result.request();
        assertThat(sent.path("source").asText()).isEqualTo("gsre-agent");
        JsonNode incidents = sent.path("incidents");
        assertThat(incidents.size()).isEqualTo(5);                       // INCIDENT 5건 (CHANGE 는 제외)
        assertThat(incidents.get(0).path("issueKey").asText()).isEqualTo("INC-1055");
        assertThat(incidents.get(0).path("issueStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(incidents.get(0).path("workDate").asText()).isEqualTo("2026-11-12");
        assertThat(incidents.get(0).path("hours").decimalValue()).isEqualByComparingTo("1.5");

        assertThat(result.response().path("syncedCount").asInt()).isEqualTo(5);
        assertThat(result.response().path("batchId").asText()).isEqualTo("INC-BATCH-261114-0001");
        assertThat(result.response().path("status").asText()).isEqualTo("ACCEPTED");

        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.direction()).isEqualTo("INBOUND");
        assertThat(log.transportType()).isEqualTo("REST");
        assertThat(log.httpMethod()).isEqualTo("POST");
        assertThat(log.rawPayloadIn()).contains("issue_key");
        assertThat(log.reshapedPayloadIn()).contains("\"incidents\"");
        assertThat(log.reshapedPayloadOut()).contains("batchId");
    }

    // ==================================================================
    // 4. BIDIRECTIONAL: 내부 조회 → webhook POST → 응답 정제
    // ==================================================================

    @Test
    @DisplayName("BIDIRECTIONAL 실행: CRITICAL 자산을 webhook 페이로드로 변환해 POST 하고 접수 결과만 남긴다")
    void restWebhookShapesBothWays() {
        McpExecutionResult result = orchestrator.execute("REST-NOTIFY-001", Map.of(), "RUN-REST-WEBHOOK-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(result.direction()).isEqualTo(McpDirection.BIDIRECTIONAL);

        Captured request = WEBHOOK.get();
        assertThat(request.path()).isEqualTo("/api/v1/webhooks/sre-alert");
        assertThat(request.header("X-Webhook-Class")).isEqualTo("sre-alert");
        // API_KEY 시드는 환경변수 이름만 갖는다. 값이 없으면 헤더만 생략하고 요청은 그대로 나간다.
        assertThat(request.header("X-Api-Key")).isNull();

        JsonNode sent = result.request();
        assertThat(sent.path("source").asText()).isEqualTo("gsre-agent");
        assertThat(sent.path("eventType").asText()).isEqualTo("asset.health");
        JsonNode alerts = sent.path("alerts");
        assertThat(alerts.size()).isEqualTo(2);
        assertThat(alerts.get(0).path("appId").asText()).isEqualTo("APP-1001");
        assertThat(alerts.get(0).path("severity").asText()).isEqualTo("CRITICAL");
        assertThat(alerts.get(0).path("openIncidents").isNumber()).isTrue();
        assertThat(alerts.get(1).path("appId").asText()).isEqualTo("APP-1002");

        assertThat(result.response().path("notified").asInt()).isEqualTo(2);
        assertThat(result.response().path("channel").asText()).isEqualTo("#sre-alerts");
        assertThat(result.response().path("receiptId").asText()).isEqualTo("WH-261114-4412");

        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.rawPayloadIn()).contains("APP-1001");
        assertThat(log.rawPayloadOut()).contains("webhook-rest-live");
    }

    // ==================================================================
    // 5. PUT 등록: 테이블 값만으로 메서드·헤더가 바뀐다
    // ==================================================================

    @Test
    @DisplayName("REST API 를 PUT + 커스텀 헤더로 등록하면 즉시 그 방식으로 호출된다")
    void registeredPutApiUsesItsOwnMethodAndHeaders() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiId", "REST-ECHO-PUT");
        body.put("apiName", "에코 PUT");
        body.put("toolName", "echo.put");
        body.put("direction", "OUTBOUND");
        body.put("transportType", "REST");
        body.put("httpMethod", "PUT");
        body.put("endpointUrl", "http://127.0.0.1:" + PORT + "/api/echo/put");
        body.put("headersConfig", "{\"X-Custom\":\"v1\",\"X-GSRE-Source\":\"incident-agent-spring-ai\"}");
        body.put("requestReshapingRule", """
                {"mode":"OBJECT","fields":[{"target":"note","source":"$.note","default":"put-test"}]}
                """);
        body.put("responseReshapingRule", """
                {"mode":"OBJECT","fields":[{"target":"method","source":"$.method"},{"target":"echoed","source":"$.echoed"}]}
                """);

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transportType").value("REST"))
                .andExpect(jsonPath("$.httpMethod").value("PUT"))
                .andExpect(jsonPath("$.headersConfig").value(org.hamcrest.Matchers.containsString("X-Custom")));

        McpExecutionResult result = orchestrator.execute("REST-ECHO-PUT", Map.of(), "RUN-REST-PUT-1", "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(result.response().path("method").asText()).isEqualTo("PUT");
        assertThat(ECHO_PUT.get().method()).isEqualTo("PUT");
        assertThat(ECHO_PUT.get().header("X-Custom")).isEqualTo("v1");
        assertThat(ECHO_PUT.get().body()).contains("\"note\":\"put-test\"");
    }

    // ==================================================================
    // 6. DELETE / PATCH: 다섯 메서드 모두 같은 규칙으로 구동된다
    // ==================================================================

    @Test
    @DisplayName("DELETE 는 경로변수+Query String, PATCH 는 JSON 본문으로 나간다")
    void deleteAndPatchKeepTheirTransportShape() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "apiId", "REST-ECHO-DELETE",
                                "apiName", "에코 DELETE",
                                "toolName", "echo.delete",
                                "direction", "OUTBOUND",
                                "transportType", "REST",
                                "httpMethod", "DELETE",
                                "endpointUrl", "http://127.0.0.1:" + PORT + "/api/echo/delete/{ticketId}",
                                "requestReshapingRule", "{\"mode\":\"OBJECT\",\"fields\":["
                                        + "{\"target\":\"ticketId\",\"source\":\"$.ticketId\",\"default\":\"INC-1055\"},"
                                        + "{\"target\":\"reason\",\"constant\":\"duplicate\"}]}",
                                "responseReshapingRule", "{\"mode\":\"OBJECT\",\"fields\":[{\"target\":\"deleted\",\"source\":\"$.deleted\"}]}"))))
                .andExpect(status().isCreated());

        McpExecutionResult deleted = orchestrator.execute("REST-ECHO-DELETE", Map.of(), null, "tester");

        assertThat(deleted.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(deleted.response().path("deleted").asBoolean()).isTrue();
        assertThat(ECHO_DELETE.get().method()).isEqualTo("DELETE");
        assertThat(ECHO_DELETE.get().path()).isEqualTo("/api/echo/delete/INC-1055");
        assertThat(ECHO_DELETE.get().query()).isEqualTo("reason=duplicate");
        assertThat(ECHO_DELETE.get().body()).isEmpty();

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "apiId", "REST-ECHO-PATCH",
                                "apiName", "에코 PATCH",
                                "toolName", "echo.patch",
                                "direction", "OUTBOUND",
                                "transportType", "REST",
                                "httpMethod", "PATCH",
                                "endpointUrl", "http://127.0.0.1:" + PORT + "/api/echo/patch",
                                "requestReshapingRule", "{\"mode\":\"OBJECT\",\"fields\":["
                                        + "{\"target\":\"status\",\"constant\":\"ACK\"}]}"))))
                .andExpect(status().isCreated());

        McpExecutionResult patched = orchestrator.execute("REST-ECHO-PATCH", Map.of(), null, "tester");

        assertThat(patched.status()).isEqualTo(McpExecutionStatus.SUCCESS);
        assertThat(ECHO_PATCH.get().method()).isEqualTo("PATCH");
        assertThat(ECHO_PATCH.get().body()).isEqualTo("{\"status\":\"ACK\"}");
    }

    // ==================================================================
    // 7. 실패를 숨기지 않는다 / 픽스처는 결정적이다
    // ==================================================================

    @Test
    @DisplayName("REST 5xx 는 폴백하지 않고 FAILED + HTTP 상태로 감사 로그에 남는다")
    void restHttpErrorIsNotHidden() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "REST-FAIL-001",
                "apiName", "실패 확인",
                "toolName", "cmdb.fail",
                "direction", "OUTBOUND",
                "transportType", "REST",
                "httpMethod", "GET",
                "endpointUrl", "http://127.0.0.1:" + PORT + "/api/fail");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        McpExecutionResult result = orchestrator.execute("REST-FAIL-001", Map.of(), null, "tester");

        assertThat(result.status()).isEqualTo(McpExecutionStatus.FAILED);
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.errorMessage()).isEqualTo("HTTP 500");
        // 가짜 성공(FALLBACK) 으로 덮지 않는다. 경로 오류는 경고로 남기고 상태는 FAILED.
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("HTTP 500"));

        McpPayloadLog log = logRepository.findByCallId(result.callId()).get(0);
        assertThat(log.statusCode()).isEqualTo(500);
        assertThat(log.errorMessage()).isEqualTo("HTTP 500");
        assertThat(log.rawPayloadOut()).contains("cmdb backend timeout");
    }

    @Test
    @DisplayName("오프라인 픽스처: REST 응답도 JSON-RPC 봉투 없이 같은 Raw → 같은 Reshaping 결과를 준다")
    void restOfflineFixtureIsDeterministic() {
        AgentProperties props = new AgentProperties();
        props.getMcp().setTransportMode("OFFLINE");
        RestApiClientGateway offlineGateway = new RestApiClientGateway(objectMapper, props, fixtures);

        McpApiMaster api = masterRepository.findActive("REST-CMDB-001").orElseThrow();
        McpCallResult first = offlineGateway.call(api, objectMapper.createObjectNode(), Map.of());
        McpCallResult second = offlineGateway.call(api, objectMapper.createObjectNode(), Map.of());

        assertThat(first.fallback()).isTrue();
        assertThat(first.statusCode()).isZero();
        assertThat(first.errorMessage()).contains("OFFLINE");
        assertThat(first.rawBody()).doesNotContain("jsonrpc");
        assertThat(first.rawBody()).contains("auditLog");
        assertThat(first.rawBody()).isEqualTo(second.rawBody());

        ReshapingResult shaped = reshaper.reshape(api.responseReshapingRule(), first.payload(), api.toolName());
        assertThat(shaped.payload().path("appName").asText()).isEqualTo("서비스맵포털");
        assertThat(shaped.payload().path("restartCount").asInt()).isEqualTo(9);
    }

    // ==================================================================
    // 8. 등록 검증 (잘못된 설정은 실행 전에 400)
    // ==================================================================

    @Test
    @DisplayName("지원하지 않는 http_method 등록은 400")
    void invalidHttpMethodIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "REST-BAD-METHOD",
                "apiName", "잘못된 메서드",
                "toolName", "bad.method",
                "direction", "OUTBOUND",
                "transportType", "REST",
                "httpMethod", "FETCH",
                "endpointUrl", "http://localhost:18080/api/x");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("http_method")));
    }

    @Test
    @DisplayName("headers_config 가 JSON 객체가 아니면 400")
    void invalidHeadersConfigIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "REST-BAD-HEADERS",
                "apiName", "잘못된 헤더",
                "toolName", "bad.headers",
                "direction", "OUTBOUND",
                "transportType", "REST",
                "httpMethod", "GET",
                "headersConfig", "[1,2,3]",
                "endpointUrl", "http://localhost:18080/api/x");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("headersConfig")));
    }

    @Test
    @DisplayName("본문을 보내지 않는 GET 으로 INBOUND 를 등록하면 400")
    void inboundWithGetIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "apiId", "REST-BAD-INBOUND",
                "apiName", "GET 인바운드",
                "toolName", "bad.inbound",
                "direction", "INBOUND",
                "transportType", "REST",
                "httpMethod", "GET",
                "endpointUrl", "http://localhost:18080/api/x",
                "sourceQuery", "SELECT app_id, app_name FROM svcm_tb_app_master");

        mockMvc.perform(post("/api/v1/mcp/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("GET")));
    }

    // ==================================================================
    // 서버 쪽 관찰 기록
    // ==================================================================

    private record Captured(String method, String path, String query, String body, Map<String, String> headers) {

        static Captured of(HttpExchange exchange) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, values) ->
                    headers.put(key.toLowerCase(), values.isEmpty() ? "" : values.get(0)));
            return new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    emptyIfNull(exchange.getRequestURI().getRawQuery()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    headers);
        }

        String header(String name) {
            return headers.get(name.toLowerCase());
        }

        private static String emptyIfNull(String value) {
            return value == null ? "" : value;
        }
    }

    private static void respond(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * CMDB REST API 원본 응답. 실제 서비스처럼 auditLog/config/dependencies 등 쓸모없는 필드가 붙어 있고,
     * 테이블의 response_reshaping_rule 이 이 중 11개만 남긴다.
     */
    private static final String CMDB_BODY = """
            {
              "requestId": "cmdb-live-test",
              "resultCode": "OK",
              "data": {
                "appId": "APP-1001",
                "appName": "서비스맵포털",
                "appType": "WEB",
                "serviceId": "SVC-01",
                "serviceName": "서비스맵포털",
                "tier": "TIER-1",
                "criticality": "CRITICAL",
                "status": "OPERATING",
                "owners": [
                  {"name": "김민수", "deptName": "SRE플랫폼팀", "email": "kim.minsu@samsung.com", "role": "OWNER"},
                  {"name": "한도윤", "deptName": "SRE플랫폼팀", "email": "han.doyun@samsung.com", "role": "OPERATOR"}
                ],
                "metrics": {"openIncidents": 1, "restartCount": 9, "cpuUsage": 41.7, "p95LatencyMs": 312},
                "dependencies": [
                  {"appId": "APP-1002", "type": "HTTP", "timeoutMs": 3000},
                  {"appId": "APP-1005", "type": "KAFKA", "topic": "svcm.portal.events"}
                ],
                "config": {"replicas": 3, "image": "registry.local/svcm/portal:2.5.1"},
                "tags": ["portal", "svcm", "tier1"],
                "auditLog": [
                  {"at": "2026-11-13T18:22:10+09:00", "by": "U1005", "action": "CONFIG_CHANGE", "detail": "replicas 2 -> 3"},
                  {"at": "2026-11-12T23:41:07+09:00", "by": "U1001", "action": "DEPLOY", "detail": "portal 2.5.0 -> 2.5.1"}
                ]
              }
            }
            """;
}
