package com.samsung.gsre.agent.state;

import com.samsung.gsre.agent.audit.repository.AuditRepository;
import com.samsung.gsre.agent.audit.repository.ConversationStore;
import com.samsung.gsre.agent.common.constant.AgentConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.llm.AgentRunScope;
import com.samsung.gsre.agent.llm.exception.LlmException;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.RunStatus;
import com.samsung.gsre.agent.state.worker.AnswerGenerationWorker;
import com.samsung.gsre.agent.state.worker.QueryUnderstandingWorker;
import com.samsung.gsre.agent.state.worker.RagCacheSearchWorker;
import com.samsung.gsre.agent.state.worker.ReuseDecisionWorker;
import com.samsung.gsre.agent.state.worker.SqlCompileWorker;
import com.samsung.gsre.agent.state.worker.SqlExecutionWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 장애 지원 질의 처리의 중심 흐름 (Orchestrator).
 *
 * <p>이 클래스는 <b>비즈니스 흐름만</b> 담는다. 프롬프트 구성, 문자열 파싱, JDBC 호출은
 * 각 Worker/Gateway 가 맡고, 여기서는 "어떤 순서로 무엇을 하는가" 만 읽히게 유지한다.</p>
 *
 * <pre>
 *  1) 질의 이해        QueryUnderstandingWorker  (LLM: 조회 계획)
 *  2) RAG 캐시 검색    RagCacheSearchWorker      (검증 SQL 후보)
 *  3) 재사용 판단      ReuseDecisionWorker       (LLM: 재사용/신규)
 *  4) SQL 준비         SqlCompileWorker          (재사용 적용 / NL2SQL)
 *  5) 검증·실행·복구   SqlExecutionWorker        (SqlGuard → DbExecutor → SelfRepair)
 *  6) 답변 생성        AnswerGenerationWorker    (LLM 1회: 충분성 + 답변)
 * </pre>
 *
 * <p>2~5는 계획된 조회 작업마다 반복되고, 6에서 결과가 부족하면 2로 돌아가 추가 조회를 한다.
 * 반복 횟수·실행 수·LLM 호출 수는 모두 상한을 두어 무한 루프를 막는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentQueryFlowService {

    // ==================================================================
    // 상단 (INPUT) — 흐름 기준정보 (바꾸려면 application.yml 의 agent.* 참고)
    // ==================================================================
    /** 안전장치: 한 질의에서 흐름을 도는 최대 횟수 */
    private static final int MAX_FLOW_ITERATIONS = AgentConstants.DEFAULT_MAX_FLOW_ITERATIONS;

    private final AgentProperties props;
    private final QueryUnderstandingWorker queryUnderstandingWorker;
    private final RagCacheSearchWorker ragCacheSearchWorker;
    private final ReuseDecisionWorker reuseDecisionWorker;
    private final SqlCompileWorker sqlCompileWorker;
    private final SqlExecutionWorker sqlExecutionWorker;
    private final AnswerGenerationWorker answerGenerationWorker;
    private final AuditRepository auditRepository;
    private final ConversationStore conversationStore;
    private final Environment environment;

    // ==================================================================
    // 중단 (PROCESS) — 6단계 처리 흐름
    // ==================================================================

    public PipelineResult handle(String userId, String channel, String sessionId, String question) {
        return handle(new AgentQueryCommand(userId, channel, sessionId, question));
    }

    public PipelineResult handle(AgentQueryCommand command) {
        PipelineContext ctx = start(command);
        auditRepository.startRun(ctx);

        AgentRunScope scope = AgentRunScope.open(ctx.getRunId(), props.getPipeline().getMaxLlmCalls());
        int llmCalls = 0;
        try {
            // 1) 질의 이해: 멀티턴 맥락을 반영해 조회 계획을 세운다.
            queryUnderstandingWorker.understand(ctx);

            int iterations = 0;
            while (iterations++ < MAX_FLOW_ITERATIONS) {
                // 2)~5) 계획된 조회 작업을 하나씩 처리한다. (RAG → 재사용판단 → SQL준비 → 실행/복구)
                executePendingLookups(ctx);

                // 6) 누적 결과가 질문을 충족하는지 판단하고 답변을 만든다. (LLM 1회)
                answerGenerationWorker.answer(ctx);

                // 부족하면 2단계로 되돌아가 추가 조회를 한 라운드 더 수행한다.
                if (!needsAnotherRound(ctx)) {
                    break;
                }
                queryUnderstandingWorker.replan(ctx);
            }
        } catch (LlmException.LlmUnavailableException e) {
            fail(ctx, llmUnavailableMessage(), e);
        } catch (LlmException.LlmBudgetExceededException e) {
            fail(ctx, "LLM 호출 한도(" + props.getPipeline().getMaxLlmCalls() + "회)를 넘어 처리를 중단했습니다. "
                    + "확인된 조회 결과만 안내드립니다.", e);
        } catch (RuntimeException e) {
            fail(ctx, "질의 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        } finally {
            llmCalls = scope.llmCalls();
            scope.close();
        }

        ctx.setDurationMs(Duration.between(ctx.getStartedAt(), Instant.now()).toMillis());
        decideStatus(ctx);
        complete(ctx, llmCalls);
        return PipelineResult.of(ctx, llmCalls);
    }

    /** 2~5단계 반복. 계획된 작업이 없거나 실행 상한에 닿으면 즉시 끝난다. */
    private void executePendingLookups(PipelineContext ctx) {
        int maxExecutions = props.getPipeline().getMaxExecutions();
        while (ctx.hasPendingTask() && ctx.getExecutionCount() < maxExecutions) {
            ragCacheSearchWorker.search(ctx);       // 2) 검증 SQL 검색
            reuseDecisionWorker.decide(ctx);        // 3) 재사용 판단
            sqlCompileWorker.compile(ctx);          // 4) SQL 준비
            sqlExecutionWorker.execute(ctx);        // 5) 검증·실행·Self-Repair
        }
        if (ctx.hasPendingTask()) {
            List<LookupTask> remaining = List.copyOf(ctx.getPendingTasks());
            ctx.getPendingTasks().clear();
            ctx.noteError("실행 상한(" + maxExecutions + ")에 도달해 남은 조회를 건너뛰었습니다: " + remaining);
            log.warn("[flow] 실행 상한 도달, 남은 작업 {}", remaining);
        }
    }

    // ==================================================================
    // 하단 (OUTPUT) — 결과 상태 · 실행 종료 처리
    // ==================================================================

    private PipelineContext start(AgentQueryCommand command) {
        String sessionId = ValidationUtils.orDefault(command.sessionId(),
                AgentConstants.SESSION_ID_PREFIX + System.nanoTime());
        String userId = ValidationUtils.orDefault(command.userId(), AgentConstants.ANONYMOUS_USER);
        String channel = ValidationUtils.orDefault(command.channel(), AgentConstants.DEFAULT_CHANNEL);
        String question = ValidationUtils.orDefault(command.question(), "").strip();

        PipelineContext ctx = new PipelineContext(nextRunId(), userId, channel, sessionId, question);
        ctx.setHistory(conversationStore.recentTurns(sessionId, props.getPipeline().getHistoryTurns()));
        log.info("[flow] 시작 runId={} sessionId={} question={}", ctx.getRunId(), sessionId, question);
        return ctx;
    }

    private boolean needsAnotherRound(PipelineContext ctx) {
        AnswerOutcome answer = ctx.getAnswer();
        if (answer == null || answer.sufficient() || answer.nextPurposes().isEmpty()) {
            return false;
        }
        if (ctx.getExtraRounds() >= props.getPipeline().getMaxExtraRounds()) {
            return false;
        }
        return ctx.getExecutionCount() < props.getPipeline().getMaxExecutions();
    }

    private void decideStatus(PipelineContext ctx) {
        // 예외로 이미 실패 처리된 run 은 성공으로 덮어쓰지 않는다.
        if (ctx.getStatus() == RunStatus.FAILED || ctx.getErrorMessage() != null) {
            ctx.setStatus(RunStatus.FAILED);
            return;
        }
        if (ctx.getAnswer() == null) {
            ctx.setStatus(RunStatus.FAILED);
            return;
        }
        if (ctx.hasFailedLookup() && !ctx.hasSuccessfulLookup()) {
            ctx.setStatus(RunStatus.FAILED);
            return;
        }
        if (ctx.hasFailedLookup() || !ctx.getAnswer().sufficient()) {
            ctx.setStatus(RunStatus.PARTIAL);
            return;
        }
        ctx.setStatus(RunStatus.SUCCESS);
    }

    private void fail(PipelineContext ctx, String message, RuntimeException cause) {
        log.error("[flow] runId={} 실패: {}", ctx.getRunId(), message, cause);
        ctx.setStatus(RunStatus.FAILED);
        ctx.setErrorMessage(message);
        ctx.setAnswer(new AnswerOutcome(false, message, List.of("실행 실패"), List.of()));
    }

    private void complete(PipelineContext ctx, int llmCalls) {
        auditRepository.finishRun(ctx, llmCalls);
        conversationStore.append(ctx.getSessionId(), "user", ctx.getQuestion(), ctx.getRunId());
        if (ctx.getAnswer() != null) {
            conversationStore.append(ctx.getSessionId(), "assistant", ctx.getAnswer().answer(), ctx.getRunId());
        }
        log.info("[flow] 종료 runId={} status={} executions={} llmCalls={} duration={}ms",
                ctx.getRunId(), ctx.getStatus(), ctx.getExecutionCount(), llmCalls, ctx.getDurationMs());
    }

    private String llmUnavailableMessage() {
        String baseUrl = environment.getProperty("spring.ai.openai.base-url",
                AgentConstants.DEFAULT_LOCAL_LLM_BASE_URL);
        return "로컬 LLM 에 연결하지 못해 조회를 시작하지 못했습니다. "
                + "Mac mini 의 모델 서버(Ollama/vLLM/llama.cpp)가 실행 중인지, "
                + "LOCAL_LLM_BASE_URL(현재 " + baseUrl + ")이 맞는지 확인해 주세요. "
                + "모델 없이 흐름을 확인하려면 AGENT_LLM_MODE=offline 으로 실행하면 됩니다.";
    }

    private String nextRunId() {
        return AgentConstants.RUN_ID_PREFIX
                + java.time.LocalDateTime.now().format(AgentConstants.ID_TIMESTAMP_FORMAT) + "-"
                + String.format(Locale.ROOT, "%04X", ThreadLocalRandom.current().nextInt(0xFFFF));
    }
}
