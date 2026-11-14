package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.mcp.McpOrchestratorService;
import com.samsung.gsre.agent.web.dto.McpApiRegisterRequest;
import com.samsung.gsre.agent.web.dto.McpApiView;
import com.samsung.gsre.agent.web.dto.McpExecuteRequest;
import com.samsung.gsre.agent.web.dto.McpExecutionView;
import com.samsung.gsre.agent.web.dto.McpPayloadLogView;
import com.samsung.gsre.agent.web.mapper.WebMapper;
import jakarta.validation.Valid;
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
 *   <li>{@code /logs} — raw/reshaped 비교 조회 (시크릿·PII 마스킹).</li>
 * </ul>
 *
 * <p>이 클래스는 HTTP 경계만 담당한다. DTO ↔ 도메인 변환은 {@link WebMapper} 가 한다.</p>
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class McpApiController {

    private final McpOrchestratorService orchestrator;

    @GetMapping("/apis")
    public List<McpApiView> apis() {
        return orchestrator.apis().stream().map(WebMapper::toApiView).toList();
    }

    @GetMapping("/apis/{apiId}")
    public McpApiView api(@PathVariable String apiId) {
        return WebMapper.toApiView(orchestrator.api(apiId));
    }

    @PostMapping("/apis")
    public ResponseEntity<McpApiView> register(@Valid @RequestBody McpApiRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WebMapper.toApiView(orchestrator.register(WebMapper.toMaster(request))));
    }

    @PostMapping("/execute/{apiId}")
    public McpExecutionView execute(@PathVariable String apiId,
                                    @RequestBody(required = false) McpExecuteRequest request) {
        McpExecuteRequest body = ValidationUtils.orDefault(request, new McpExecuteRequest(null, null, null));
        return WebMapper.toExecutionView(
                orchestrator.execute(apiId, body.paramsOrEmpty(), body.runId(), body.requestedBy()));
    }

    @GetMapping("/logs")
    public List<McpPayloadLogView> logs(@RequestParam(required = false) String apiId,
                                        @RequestParam(defaultValue = "20") int limit) {
        return orchestrator.logs(apiId, limit).stream().map(WebMapper::toPayloadLogView).toList();
    }
}
