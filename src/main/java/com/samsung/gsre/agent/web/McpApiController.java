package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.McpDirection;
import com.samsung.gsre.agent.mcp.McpExecutionResult;
import com.samsung.gsre.agent.mcp.McpOrchestratorService;
import com.samsung.gsre.agent.mcp.McpPayloadLog;
import com.samsung.gsre.agent.web.dto.McpApiRegisterRequest;
import com.samsung.gsre.agent.web.dto.McpApiView;
import com.samsung.gsre.agent.web.dto.McpExecuteRequest;
import com.samsung.gsre.agent.web.dto.McpExecutionView;
import com.samsung.gsre.agent.web.dto.McpPayloadLogView;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP/REST API 확장 관리 + 실행 API.
 *
 * <ul>
 *   <li>{@code /apis} — 도구 등록/조회. 코드 배포 없이 새 연동(MCP·REST)을 추가하는 창구.</li>
 *   <li>{@code /execute/{apiId}} — 등록된 룰대로 Reshaping 후 통신하고, 감사 로그를 남긴다.</li>
 *   <li>{@code /logs} — raw/reshaped 비교 조회.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpApiController {

    private final McpOrchestratorService orchestrator;

    @GetMapping("/apis")
    public List<McpApiView> apis() {
        return orchestrator.apis().stream().map(McpApiController::toView).toList();
    }

    @GetMapping("/apis/{apiId}")
    public McpApiView api(@PathVariable String apiId) {
        return toView(orchestrator.api(apiId));
    }

    @PostMapping("/apis")
    public ResponseEntity<McpApiView> register(@Valid @RequestBody McpApiRegisterRequest request) {
        McpApiMaster api = new McpApiMaster(
                request.apiId().trim(),
                request.apiName(),
                request.toolName(),
                McpDirection.from(request.direction()),
                request.transportType(),
                request.endpointUrl(),
                request.authType(),
                request.authConfig(),
                request.requestSchema(),
                request.requestReshapingRule(),
                request.responseReshapingRule(),
                request.sourceQuery(),
                request.timeoutMs() == null ? 5_000 : request.timeoutMs(),
                request.active() == null || request.active(),
                request.description(),
                request.createdBy(),
                Instant.now(),
                Instant.now(),
                request.httpMethod(),
                request.headersConfig());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(orchestrator.register(api)));
    }

    @PostMapping("/execute/{apiId}")
    public McpExecutionView execute(@PathVariable String apiId,
                                    @RequestBody(required = false) McpExecuteRequest request) {
        McpExecuteRequest body = request == null ? new McpExecuteRequest(null, null, null) : request;
        return toView(orchestrator.execute(apiId, body.paramsOrEmpty(), body.runId(), body.requestedBy()));
    }

    @GetMapping("/logs")
    public List<McpPayloadLogView> logs(@RequestParam(required = false) String apiId,
                                        @RequestParam(defaultValue = "20") int limit) {
        return orchestrator.logs(apiId, limit).stream().map(McpApiController::toView).toList();
    }

    // ------------------------------------------------------------------

    private static McpApiView toView(McpApiMaster api) {
        return new McpApiView(
                api.apiId(), api.apiName(), api.toolName(), api.direction().name(), api.transportType(),
                api.endpointUrl(), api.authType(), api.timeoutMs(), api.active(), api.description(),
                api.requestSchema(), api.requestReshapingRule(), api.responseReshapingRule(),
                api.sourceQuery(), api.createdAt(), api.httpMethod(), api.headersConfig());
    }

    private static McpExecutionView toView(McpExecutionResult result) {
        return new McpExecutionView(
                result.callId(), result.logId(), result.apiId(), result.apiName(), result.toolName(),
                result.direction().name(), result.status().name(), result.statusCode(), result.durationMs(),
                result.request(), result.response(), result.stages(), result.warnings(), result.errorMessage(),
                result.transportType(), result.httpMethod());
    }

    private static McpPayloadLogView toView(McpPayloadLog log) {
        return new McpPayloadLogView(
                log.logId(), log.callId(), log.runId(), log.apiId(), log.direction(), log.statusCode(),
                log.durationMs(), log.errorMessage(), log.createdAt(),
                log.rawPayloadIn(), log.reshapedPayloadIn(), log.rawPayloadOut(), log.reshapedPayloadOut(),
                log.transportType(), log.httpMethod());
    }
}
