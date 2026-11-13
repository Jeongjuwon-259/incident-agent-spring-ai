package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.audit.AuditRepository;
import com.samsung.gsre.agent.state.AgentQueryCommand;
import com.samsung.gsre.agent.state.IncidentQueryFlowService;
import com.samsung.gsre.agent.state.PipelineResult;
import com.samsung.gsre.agent.web.dto.QueryRequest;
import com.samsung.gsre.agent.web.dto.QueryResponse;
import com.samsung.gsre.agent.web.dto.RunDetailView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 질의 API. */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final IncidentQueryFlowService flowService;
    private final AuditRepository auditRepository;

    /** 자연어 질의 → 6단계 파이프라인 실행 → 답변 + 실행 추적 반환. */
    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        PipelineResult result = flowService.handle(new AgentQueryCommand(
                request.userId(), request.channel(), request.sessionId(), request.question()));
        return WebMapper.toResponse(result);
    }

    /** 실행 1건의 단계별 이력 + LLM 호출 이력 (개발 디버깅용). */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunDetailView> run(@PathVariable String runId) {
        List<Map<String, Object>> run = auditRepository.findRun(runId);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new RunDetailView(run.get(0),
                auditRepository.findSteps(runId), auditRepository.findLlmCalls(runId)));
    }

    /** 세션의 최근 실행 목록. */
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam String sessionId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return auditRepository.findRunsBySession(sessionId, Math.min(Math.max(limit, 1), 100));
    }
}
