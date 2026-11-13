package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.eval.EvalBatchStart;
import com.samsung.gsre.agent.eval.EvalService;
import com.samsung.gsre.agent.eval.EvalTestCase;
import com.samsung.gsre.agent.web.dto.EvalBatchRequest;
import com.samsung.gsre.agent.web.dto.EvalBatchView;
import com.samsung.gsre.agent.web.dto.EvalCaseRequest;
import com.samsung.gsre.agent.web.dto.EvalResultView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 품질 평가 API (요구사항 §6.3).
 *
 * <p>테스트 케이스 관리 → 평가 실행(비동기 배치) → 배치/TC별 결과 조회 → TC 이력 조회.</p>
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    @GetMapping("/cases")
    public List<EvalTestCase> cases(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return evalService.cases(enabledOnly);
    }

    @GetMapping("/cases/{tcId}")
    public EvalTestCase caseDetail(@PathVariable String tcId) {
        return evalService.caseById(tcId);
    }

    @PostMapping("/cases/{tcId}")
    public EvalTestCase createCase(@PathVariable String tcId, @Valid @RequestBody EvalCaseRequest request) {
        return evalService.saveCase(tcId, request.question(), request.expectedSql(), request.expectedAnswer(),
                request.category(), request.enabled() == null || request.enabled());
    }

    @PutMapping("/cases/{tcId}")
    public EvalTestCase updateCase(@PathVariable String tcId, @Valid @RequestBody EvalCaseRequest request) {
        return evalService.saveCase(tcId, request.question(), request.expectedSql(), request.expectedAnswer(),
                request.category(), request.enabled() == null || request.enabled());
    }

    /** 평가 실행 접수. 비동기로 돌고 Batch ID 로 진행 상태를 조회한다. */
    @PostMapping("/batches")
    public ResponseEntity<EvalBatchStart> startBatch(@RequestBody(required = false) EvalBatchRequest request) {
        EvalBatchRequest body = request == null ? new EvalBatchRequest(null, true, null) : request;
        EvalBatchStart start = evalService.startBatch(body.tcIds(), body.all() == null || body.all(),
                body.requestedBy() == null ? "web" : body.requestedBy());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(start);
    }

    @GetMapping("/batches")
    public List<EvalBatchView> batches(@RequestParam(defaultValue = "20") int limit) {
        return evalService.batches(Math.min(Math.max(limit, 1), 100)).stream()
                .map(WebMapper::toBatchView).toList();
    }

    @GetMapping("/batches/{batchId}")
    public EvalBatchView batch(@PathVariable String batchId) {
        return WebMapper.toBatchView(evalService.batch(batchId));
    }

    @GetMapping("/batches/{batchId}/results")
    public List<EvalResultView> results(@PathVariable String batchId) {
        return evalService.results(batchId).stream().map(WebMapper::toResultView).toList();
    }

    /** 같은 TC 의 과거 평가 이력 (품질 변화 비교). */
    @GetMapping("/cases/{tcId}/history")
    public List<EvalResultView> history(@PathVariable String tcId,
                                        @RequestParam(defaultValue = "20") int limit) {
        return evalService.caseHistory(tcId, Math.min(Math.max(limit, 1), 100)).stream()
                .map(WebMapper::toResultView).toList();
    }
}
