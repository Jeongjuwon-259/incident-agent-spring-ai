package com.samsung.gsre.agent.eval;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.gateway.DbExecutor;
import com.samsung.gsre.agent.gateway.GuardVerdict;
import com.samsung.gsre.agent.gateway.SqlGuard;
import com.samsung.gsre.agent.llm.JsonSupport;
import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.llm.PromptBuilder;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.SqlExecutionResult;
import com.samsung.gsre.agent.state.IncidentQueryFlowService;
import com.samsung.gsre.agent.state.PipelineResult;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 품질 평가 하네스 (요구사항 §6).
 *
 * <h2>기준정보 (상단)</h2>
 * <ul>
 *   <li>평가 단위: 질문 1건, 지표: EX / AA / FN / 실행 성공 여부 (각 Pass=1, Fail=0)</li>
 *   <li>종합 판정: 4개 지표 모두 Pass 일 때만 Pass</li>
 *   <li>결과는 덮어쓰지 않고 배치 단위로 누적 (과거 이력 보존, 스냅샷 보존)</li>
 *   <li>평가 실행은 비동기(202 Accepted) — 웹은 Batch ID 로 진행 상태를 조회한다</li>
 * </ul>
 *
 * <h2>가공 흐름 (중단)</h2>
 * <ol>
 *   <li>선택된 테스트 케이스로 Batch 를 만들고 비동기 실행을 접수한다.</li>
 *   <li>케이스마다 실제 Agent(IncidentQueryFlowService)를 호출해 SQL·결과·답변을 수집한다.</li>
 *   <li>EX(결과셋 비교), AA·FN(LLM-as-a-Judge), 실행 성공을 판정한다.</li>
 *   <li>지표별/종합 결과와 사유를 저장하고 배치 통과율을 갱신한다.</li>
 * </ol>
 *
 * <h2>결과 (하단)</h2>
 * {@link EvalCaseResult} · {@link EvalBatch} — 웹 조회 스펙.
 */
@Slf4j
@Service
public class EvalService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EvalRepository repository;
    private final IncidentQueryFlowService flowService;
    private final LlmGateway llmGateway;
    private final PromptBuilder promptBuilder;
    private final SqlGuard sqlGuard;
    private final DbExecutor dbExecutor;
    private final ResultSetComparator comparator;
    private final AgentProperties props;
    private final TaskExecutor evalExecutor;

    public EvalService(EvalRepository repository,
                       IncidentQueryFlowService flowService,
                       LlmGateway llmGateway,
                       PromptBuilder promptBuilder,
                       SqlGuard sqlGuard,
                       DbExecutor dbExecutor,
                       ResultSetComparator comparator,
                       AgentProperties props,
                       @Qualifier("evalExecutor") TaskExecutor evalExecutor) {
        this.repository = repository;
        this.flowService = flowService;
        this.llmGateway = llmGateway;
        this.promptBuilder = promptBuilder;
        this.sqlGuard = sqlGuard;
        this.dbExecutor = dbExecutor;
        this.comparator = comparator;
        this.props = props;
        this.evalExecutor = evalExecutor;
    }

    // ------------------------------------------------------------------
    // 평가 실행 접수 / 비동기 배치
    // ------------------------------------------------------------------

    public EvalBatchStart startBatch(List<String> tcIds, boolean all, String requestedBy) {
        List<EvalTestCase> cases = selectCases(tcIds, all);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("평가할 테스트 케이스가 없습니다. TC 를 등록하거나 선택해 주세요.");
        }
        String batchId = "EVAL-" + java.time.LocalDateTime.now().format(BATCH_FORMAT) + "-"
                + String.format(Locale.ROOT, "%03X", ThreadLocalRandom.current().nextInt(0xFFF));
        repository.insertBatch(batchId, requestedBy, cases.size());
        evalExecutor.execute(() -> runBatch(batchId, cases));
        log.info("[eval] 배치 접수 batchId={} cases={} by={}", batchId, cases.size(), requestedBy);
        return new EvalBatchStart(batchId, STATUS_RUNNING, cases.size());
    }

    /** 워커 스레드에서 실행되는 배치 본문. 케이스 1건 실패가 배치 전체를 멈추지 않는다. */
    void runBatch(String batchId, List<EvalTestCase> cases) {
        long started = System.nanoTime();
        int completed = 0;
        int passed = 0;
        String note = null;
        try {
            for (EvalTestCase tc : cases) {
                EvalCaseResult result;
                try {
                    result = evaluateCase(batchId, tc);
                } catch (RuntimeException e) {
                    log.warn("[eval] 케이스 평가 실패 batchId={} tc={}: {}", batchId, tc.tcId(), e.getMessage());
                    result = failedResult(batchId, tc, e);
                }
                repository.insertResult(result);
                completed++;
                if (result.overallPass()) {
                    passed++;
                }
                repository.updateProgress(batchId, completed, passed);
            }
            repository.finishBatch(batchId, STATUS_COMPLETED, cases.size(), completed, passed, note);
            log.info("[eval] 배치 완료 batchId={} {}/{} pass ({}ms)", batchId, passed, cases.size(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.error("[eval] 배치 실패 batchId={}", batchId, e);
            repository.finishBatch(batchId, STATUS_FAILED, cases.size(), completed, passed,
                    "배치 실행 실패: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 케이스 1건 평가 (EX / AA / FN / 실행 성공)
    // ------------------------------------------------------------------

    public EvalCaseResult evaluateCase(String batchId, EvalTestCase tc) {
        long started = System.nanoTime();
        PipelineResult run = flowService.handle("eval-harness", "EVAL", "eval-" + batchId + "-" + tc.tcId(),
                tc.question());

        List<LookupResult> succeeded = run.lookups().stream().filter(r -> !r.failed()).toList();
        List<SqlExecutionResult> actualResults = succeeded.stream().map(LookupResult::result).toList();
        String actualSql = run.lookups().isEmpty() ? null : run.lookups().get(run.lookups().size() - 1).sql();
        String actualResultJson = JsonSupport.write(succeeded.isEmpty()
                ? List.of()
                : succeeded.get(succeeded.size() - 1).result().rows());

        boolean execPass = !actualResults.isEmpty();
        String execReason = execPass ? "SQL 실행 성공(" + actualResults.size() + "건 조회)"
                : failureReason(run);

        GuardVerdict expectedGuard = sqlGuard.validate(tc.expectedSql());
        boolean exPass = false;
        String exReason;
        if (!expectedGuard.valid()) {
            exReason = "예상 SQL 이 검증 규칙을 통과하지 못했습니다: " + expectedGuard.violations();
        } else if (!execPass) {
            exReason = "실제 SQL 실행 결과가 없어 비교할 수 없습니다.";
        } else {
            try {
                SqlExecutionResult expected = dbExecutor.execute(expectedGuard, Map.of());
                exPass = comparator.matchesAny(expected, actualResults);
                exReason = exPass
                        ? "예상 SQL 결과셋과 실제 결과셋이 일치합니다(행 " + expected.rowCount() + "건)."
                        : "결과셋이 다릅니다. 예상 " + expected.rowCount() + "건 / 실제 "
                        + actualResults.stream().mapToInt(SqlExecutionResult::rowCount).sum() + "건";
            } catch (RuntimeException e) {
                exReason = "예상 SQL 실행 실패: " + e.getMessage();
            }
        }

        JudgeVerdict aa = tc.expectedAnswer() == null || tc.expectedAnswer().isBlank()
                ? JudgeVerdict.fail("예상 답변이 없어 AA 를 판정할 수 없습니다.")
                : llmGateway.judgeAnswer(tc.question(), tc.expectedAnswer(), run.answer());
        JudgeVerdict fn = llmGateway.judgeFaithfulness(tc.question(),
                promptBuilder.resultContext(run.lookups(), props.getEval().getJudgeTruncateRows()),
                run.answer());

        boolean overall = exPass && aa.pass() && fn.pass() && execPass;
        String overallReason = (overall ? "4개 지표 모두 Pass" : "일부 지표 Fail")
                + " | EX=" + flag(exPass) + " AA=" + flag(aa.pass()) + " FN=" + flag(fn.pass())
                + " EXEC=" + flag(execPass);

        return new EvalCaseResult(batchId, tc.tcId(), run.runId(), actualSql, actualResultJson,
                run.answer(), tc.question(), tc.expectedSql(), tc.expectedAnswer(),
                exPass, exReason, aa.pass(), aa.reason(), fn.pass(), fn.reason(),
                execPass, execReason, overall, overallReason,
                (System.nanoTime() - started) / 1_000_000, Instant.now());
    }

    // ------------------------------------------------------------------
    // 조회 (웹 API 스펙)
    // ------------------------------------------------------------------

    public List<EvalTestCase> cases(boolean enabledOnly) {
        return repository.findCases(enabledOnly);
    }

    public EvalTestCase caseById(String tcId) {
        return repository.findCase(tcId).orElseThrow(
                () -> new IllegalArgumentException("테스트 케이스를 찾을 수 없습니다: " + tcId));
    }

    public EvalTestCase saveCase(String tcId, String question, String expectedSql, String expectedAnswer,
                                 String category, boolean enabled) {
        EvalTestCase existing = repository.findCase(tcId).orElse(null);
        EvalTestCase tc = new EvalTestCase(tcId, question, expectedSql, expectedAnswer,
                category == null ? "GENERAL" : category, enabled,
                existing == null ? Instant.now() : existing.createdAt(), Instant.now());
        if (existing == null) {
            repository.insertCase(tc);
        } else {
            repository.updateCase(tc);
        }
        return tc;
    }

    public EvalBatch batch(String batchId) {
        return repository.findBatch(batchId).orElseThrow(
                () -> new IllegalArgumentException("평가 배치를 찾을 수 없습니다: " + batchId));
    }

    public List<EvalBatch> batches(int limit) {
        return repository.findBatches(limit);
    }

    public List<EvalCaseResult> results(String batchId) {
        return repository.findResults(batchId);
    }

    public List<EvalCaseResult> caseHistory(String tcId, int limit) {
        return repository.findCaseHistory(tcId, limit);
    }

    // ------------------------------------------------------------------
    // 내부 유틸
    // ------------------------------------------------------------------

    private List<EvalTestCase> selectCases(List<String> tcIds, boolean all) {
        List<EvalTestCase> selected = new ArrayList<>();
        if (all || tcIds == null || tcIds.isEmpty()) {
            selected.addAll(repository.findCases(true));
        } else {
            for (String tcId : tcIds) {
                repository.findCase(tcId).ifPresent(selected::add);
            }
        }
        int max = props.getEval().getMaxCasesPerBatch();
        return selected.size() > max ? selected.subList(0, max) : selected;
    }

    private EvalCaseResult failedResult(String batchId, EvalTestCase tc, RuntimeException e) {
        String reason = "평가 실행 오류: " + e.getMessage();
        return new EvalCaseResult(batchId, tc.tcId(), null, null, null, null,
                tc.question(), tc.expectedSql(), tc.expectedAnswer(),
                false, reason, false, reason, false, reason, false, reason, false, reason, 0L, Instant.now());
    }

    private String failureReason(PipelineResult run) {
        String error = run.lookups().stream().filter(LookupResult::failed)
                .map(LookupResult::errorMessage).findFirst().orElse(null);
        if (error != null) {
            return "SQL 실행 실패: " + error;
        }
        return "SQL 실행 결과가 없습니다.";
    }

    private String flag(boolean pass) {
        return pass ? "Pass" : "Fail";
    }
}
