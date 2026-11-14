package com.samsung.gsre.agent.llm.gateway;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.eval.metric.HeuristicJudge;
import com.samsung.gsre.agent.llm.AgentRunScope;
import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.llm.exception.LlmException;
import com.samsung.gsre.agent.llm.prompt.LlmPrompt;
import com.samsung.gsre.agent.llm.prompt.PromptBuilder;
import com.samsung.gsre.agent.llm.support.JsonSupport;
import com.samsung.gsre.agent.llm.support.LlmCallRecord;
import com.samsung.gsre.agent.llm.support.LlmCallRecorder;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.SqlOrigin;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicAnswerComposer;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicQueryPlanner;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicSqlTemplates;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring AI ChatClient 를 사용하는 실제 LLM 게이트웨이.
 *
 * <p>로컬 모델은 출력 형식이 흔들리기 쉬우므로, JSON 파싱에 실패하면 같은 단계의 규칙 기반 구현으로
 * 폴백하고 그 사실을 로그로 남긴다. (실행 추적성은 유지되고 파이프라인은 멈추지 않는다.)</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.llm.mode", havingValue = "local", matchIfMissing = true)
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final PromptBuilder promptBuilder;
    private final LlmCallRecorder recorder;
    private final AgentProperties props;
    private final HeuristicQueryPlanner fallbackPlanner;
    private final HeuristicSqlTemplates fallbackSql;
    private final HeuristicAnswerComposer fallbackAnswer;
    private final HeuristicJudge fallbackJudge;
    private final String model;

    public SpringAiLlmGateway(ChatClient chatClient,
                              PromptBuilder promptBuilder,
                              LlmCallRecorder recorder,
                              AgentProperties props,
                              HeuristicQueryPlanner fallbackPlanner,
                              HeuristicSqlTemplates fallbackSql,
                              HeuristicAnswerComposer fallbackAnswer,
                              HeuristicJudge fallbackJudge,
                              @Value("${spring.ai.openai.chat.options.model:local-model}") String model) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.recorder = recorder;
        this.props = props;
        this.fallbackPlanner = fallbackPlanner;
        this.fallbackSql = fallbackSql;
        this.fallbackAnswer = fallbackAnswer;
        this.fallbackJudge = fallbackJudge;
        this.model = model;
    }

    @Override
    public String provider() {
        return "spring-ai-openai:" + model;
    }

    @Override
    public QueryPlan understand(String question, List<ConversationTurn> history) {
        String raw = invoke("UNDERSTAND", promptBuilder.understand(question, history));
        QueryPlan plan = promptBuilder.parsePlan(raw);
        if (plan == null) {
            log.warn("[llm] UNDERSTAND 응답 파싱 실패 -> 규칙 기반 폴백");
            return fallbackPlanner.plan(question, history);
        }
        return plan;
    }

    @Override
    public ReuseDecision judgeReuse(String question, QueryPlan plan, LookupTask task,
                                    List<CacheCandidate> candidates) {
        if (candidates.isEmpty()) {
            return ReuseDecision.reject("검증 SQL 후보가 없습니다.");
        }
        String raw = invoke("REUSE_DECISION", promptBuilder.reuse(question, task, candidates));
        ReuseDecision decision = promptBuilder.parseReuse(raw, candidates);
        if (decision == null) {
            log.warn("[llm] REUSE_DECISION 응답 파싱 실패 -> 규칙 기반 폴백");
            return fallbackPlanner.decideReuse(task, candidates);
        }
        return decision;
    }

    @Override
    public PreparedSql generateSql(String question, QueryPlan plan, LookupTask task,
                                   List<CacheCandidate> similar) {
        String raw = invoke("NL2SQL", promptBuilder.nl2sql(question, task, similar));
        PreparedSql sql = promptBuilder.parsePrepared(raw, SqlOrigin.GENERATED, null);
        if (sql == null) {
            log.warn("[llm] NL2SQL 응답 파싱 실패 -> 규칙 기반 폴백");
            return fallbackSql.build(question, plan, task);
        }
        return sql;
    }

    @Override
    public PreparedSql repairSql(String question, LookupTask task, PreparedSql failed, String errorMessage,
                                 List<CacheCandidate> similar) {
        String raw = invoke("SELF_REPAIR", promptBuilder.repair(failed, errorMessage, task, similar));
        PreparedSql sql = promptBuilder.parsePrepared(raw, SqlOrigin.REPAIRED, failed.cacheId());
        if (sql == null) {
            log.warn("[llm] SELF_REPAIR 응답 파싱 실패 -> 규칙 기반 폴백");
            return fallbackSql.repair(failed, errorMessage);
        }
        return sql;
    }

    @Override
    public AnswerOutcome composeAnswer(String question, List<LookupTask> plannedTasks,
                                       List<LookupResult> results, List<ConversationTurn> history) {
        if (props.getPipeline().getMaxLlmCalls() > 0 && scopeBudgetExceeded()) {
            return fallbackAnswer.compose(question, results);
        }
        String raw = invoke("ANSWER", promptBuilder.answer(question, plannedTasks, results, history));
        AnswerOutcome outcome = promptBuilder.parseAnswer(raw);
        if (outcome == null) {
            log.warn("[llm] ANSWER 응답 파싱 실패 -> 규칙 기반 폴백");
            return fallbackAnswer.compose(question, results);
        }
        return outcome;
    }

    @Override
    public JudgeVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
        String raw = invoke("JUDGE_AA", promptBuilder.judgeAnswer(question, expectedAnswer, actualAnswer));
        JudgeVerdict verdict = promptBuilder.parseVerdict(raw);
        return verdict == null ? fallbackJudge.answer(question, expectedAnswer, actualAnswer) : verdict;
    }

    @Override
    public JudgeVerdict judgeFaithfulness(String question, String resultContext, String answer) {
        String raw = invoke("JUDGE_FN", promptBuilder.judgeFaithfulness(question, resultContext, answer));
        JudgeVerdict verdict = promptBuilder.parseVerdict(raw);
        return verdict == null ? fallbackJudge.faithfulness(resultContext, answer) : verdict;
    }

    // ------------------------------------------------------------------

    private boolean scopeBudgetExceeded() {
        AgentRunScope scope = AgentRunScope.current();
        return scope != null && scope.budgetExceeded();
    }

    private String invoke(String purpose, LlmPrompt prompt) {
        AgentRunScope scope = AgentRunScope.current();
        if (scope != null && scope.budgetExceeded()) {
            throw new LlmException.LlmBudgetExceededException(
                    "LLM 호출 예산(" + props.getPipeline().getMaxLlmCalls() + "회)을 초과했습니다.");
        }
        long started = System.nanoTime();
        String response = null;
        Throwable failure = null;
        try {
            response = chatClient.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .options(OpenAiChatOptions.builder()
                            .withTemperature(prompt.temperature())
                            .withMaxTokens(prompt.maxTokens())
                            .build())
                    .call()
                    .content();
            if (response == null) {
                throw new LlmException("LLM 응답이 비어 있습니다. (purpose=" + purpose + ")");
            }
            return response;
        } catch (RuntimeException e) {
            failure = e;
            throw translate(e, purpose);
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            if (scope != null) {
                scope.incrementLlmCalls();
                scope.addLatency(latencyMs);
            }
            int promptChars = prompt.system().length() + prompt.user().length();
            int responseChars = response == null ? 0 : response.length();
            recorder.record(new LlmCallRecord(
                    AgentRunScope.currentRunId(),
                    purpose,
                    provider(),
                    model,
                    JsonSupport.truncate("[SYSTEM]\n" + prompt.system() + "\n[USER]\n" + prompt.user(), 8_000),
                    JsonSupport.truncate(response, 8_000),
                    latencyMs,
                    promptChars,
                    responseChars,
                    (promptChars + responseChars) / 3,
                    failure == null,
                    failure == null ? null : failure.getMessage(),
                    Instant.now()));
        }
    }

    private LlmException translate(RuntimeException e, String purpose) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = message.toLowerCase();
        boolean connection = lower.contains("connection refused")
                || lower.contains("failed to connect")
                || lower.contains("unknownhost")
                || lower.contains("connect timed out")
                || lower.contains("i/o error")
                || lower.contains("resourceaccessexception");
        if (connection) {
            return new LlmException.LlmUnavailableException(
                    "로컬 LLM 엔드포인트에 연결할 수 없습니다. (purpose=" + purpose + ", 원인=" + message + ")", e);
        }
        return new LlmException("LLM 호출 실패 (purpose=" + purpose + "): " + message, e);
    }
}
