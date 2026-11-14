package com.samsung.gsre.agent.web.mapper;

import com.samsung.gsre.agent.common.constant.AgentConstants;
import com.samsung.gsre.agent.eval.EvalBatch;
import com.samsung.gsre.agent.eval.EvalCaseResult;
import com.samsung.gsre.agent.mcp.McpApiMaster;
import com.samsung.gsre.agent.mcp.McpDirection;
import com.samsung.gsre.agent.mcp.McpExecutionResult;
import com.samsung.gsre.agent.mcp.repository.McpPayloadLog;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.rag.VerifiedSqlEntry;
import com.samsung.gsre.agent.security.mask.SensitiveDataMasker;
import com.samsung.gsre.agent.state.PipelineResult;
import com.samsung.gsre.agent.state.trace.TraceStep;
import com.samsung.gsre.agent.web.dto.CacheEntryView;
import com.samsung.gsre.agent.web.dto.EvalBatchView;
import com.samsung.gsre.agent.web.dto.EvalResultView;
import com.samsung.gsre.agent.web.dto.LookupView;
import com.samsung.gsre.agent.web.dto.McpApiRegisterRequest;
import com.samsung.gsre.agent.web.dto.McpApiView;
import com.samsung.gsre.agent.web.dto.McpExecutionView;
import com.samsung.gsre.agent.web.dto.McpPayloadLogView;
import com.samsung.gsre.agent.web.dto.QueryResponse;
import com.samsung.gsre.agent.web.dto.TraceView;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 도메인 결과 → 웹 응답 변환. Controller 가 비즈니스 흐름에만 집중하도록 분리한다.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <p>미리보기 행 수 등 응답 규격 상수.</p>
 *
 * <h2>중단 (PROCESS) — 변환</h2>
 * <p>요청 DTO → 도메인, 도메인 → 응답 DTO.</p>
 *
 * <h2>하단 (OUTPUT) — 응답 DTO 조립</h2>
 * <p>시크릿/토큰/PII 는 응답으로 내보내기 전에 마스킹한다.</p>
 */
public final class WebMapper {

    private static final int PREVIEW_ROWS = AgentConstants.DEFAULT_WEB_PREVIEW_ROWS;
    private static final int DEFAULT_MCP_TIMEOUT_MS = AgentConstants.DEFAULT_MCP_TIMEOUT_MS;

    private WebMapper() {
    }

    // ==================================================================
    // 중단 (PROCESS) — 질의·캐시
    // ==================================================================

    public static QueryResponse toResponse(PipelineResult result) {
        return new QueryResponse(
                result.runId(),
                result.sessionId(),
                result.status() == null ? null : result.status().name(),
                result.answer(),
                result.cacheHit(),
                result.plan() == null ? null : result.plan().intent().name(),
                result.llmCalls(),
                result.durationMs(),
                result.errorMessage(),
                result.lookups().stream().map(WebMapper::toLookup).toList(),
                result.trace().stream().map(WebMapper::toTrace).toList());
    }

    private static LookupView toLookup(LookupResult lookup) {
        List<Map<String, Object>> rows = lookup.result() == null ? List.of() : lookup.result().rows();
        return new LookupView(
                lookup.taskId(),
                lookup.purpose(),
                lookup.sql(),
                lookup.parameters(),
                lookup.origin() == null ? null : lookup.origin().name(),
                rows.size(),
                rows.size() > PREVIEW_ROWS ? rows.subList(0, PREVIEW_ROWS) : rows,
                lookup.repairNotes(),
                lookup.failed(),
                lookup.errorMessage());
    }

    private static TraceView toTrace(TraceStep step) {
        return new TraceView(step.seq(), step.stage().name(), step.stage().label(),
                step.summary(), step.latencyMs());
    }

    public static CacheEntryView toCacheView(VerifiedSqlEntry entry) {
        return new CacheEntryView(entry.id(), entry.question(), entry.sql(), entry.tables(),
                entry.parameters(), entry.verifiedBy(), entry.notes(), entry.useCount(), entry.createdAt());
    }

    public static CacheEntryView toCacheView(CacheCandidate candidate) {
        return new CacheEntryView(candidate.id(), candidate.question(), candidate.sql(), candidate.tables(),
                candidate.defaultParams(), candidate.verifiedBy(), candidate.notes(), 0, Instant.now());
    }

    // ==================================================================
    // 중단 (PROCESS) — 평가
    // ==================================================================

    public static EvalBatchView toBatchView(EvalBatch batch) {
        return new EvalBatchView(batch.batchId(), batch.status(), batch.requestedBy(),
                batch.totalCount(), batch.completedCount(), batch.passedCount(), batch.passRate(),
                batch.startedAt(), batch.finishedAt(), batch.note());
    }

    public static EvalResultView toResultView(EvalCaseResult r) {
        return new EvalResultView(r.batchId(), r.tcId(), r.agentRunId(), r.actualSql(), r.finalAnswer(),
                r.questionSnapshot(), r.expectedSqlSnapshot(), r.expectedAnswerSnapshot(),
                r.exPass(), r.exReason(), r.aaPass(), r.aaReason(), r.fnPass(), r.fnReason(),
                r.execPass(), r.execReason(), r.overallPass(), r.overallReason(),
                r.durationMs(), r.createdAt());
    }

    // ==================================================================
    // 중단 (PROCESS) — MCP/REST 등록·실행
    // ==================================================================

    /** 등록 요청 DTO → 도메인 모델. 생성·수정 시각과 기본 타임아웃을 여기서 채운다. */
    public static McpApiMaster toMaster(McpApiRegisterRequest request) {
        return new McpApiMaster(
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
                request.timeoutMs() == null ? DEFAULT_MCP_TIMEOUT_MS : request.timeoutMs(),
                request.active() == null || request.active(),
                request.description(),
                request.createdBy(),
                Instant.now(),
                Instant.now(),
                request.httpMethod(),
                request.headersConfig());
    }

    // ==================================================================
    // 하단 (OUTPUT) — 응답 DTO (시크릿 마스킹)
    // ==================================================================

    public static McpApiView toApiView(McpApiMaster api) {
        return new McpApiView(
                api.apiId(), api.apiName(), api.toolName(), api.direction().name(), api.transportType(),
                api.endpointUrl(), api.authType(), api.timeoutMs(), api.active(), api.description(),
                api.requestSchema(), api.requestReshapingRule(), api.responseReshapingRule(),
                api.sourceQuery(), api.createdAt(), api.httpMethod(),
                SensitiveDataMasker.maskHeaders(api.headersConfig()));
    }

    public static McpExecutionView toExecutionView(McpExecutionResult result) {
        return new McpExecutionView(
                result.callId(), result.logId(), result.apiId(), result.apiName(), result.toolName(),
                result.direction().name(), result.status().name(), result.statusCode(), result.durationMs(),
                result.request(), result.response(), result.stages(), result.warnings(), result.errorMessage(),
                result.transportType(), result.httpMethod());
    }

    public static McpPayloadLogView toPayloadLogView(McpPayloadLog log) {
        return new McpPayloadLogView(
                log.logId(), log.callId(), log.runId(), log.apiId(), log.direction(), log.statusCode(),
                log.durationMs(), SensitiveDataMasker.maskText(log.errorMessage()), log.createdAt(),
                SensitiveDataMasker.maskJson(log.rawPayloadIn()),
                SensitiveDataMasker.maskJson(log.reshapedPayloadIn()),
                SensitiveDataMasker.maskJson(log.rawPayloadOut()),
                SensitiveDataMasker.maskJson(log.reshapedPayloadOut()),
                log.transportType(), log.httpMethod());
    }
}
