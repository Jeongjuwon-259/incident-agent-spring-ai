package com.samsung.gsre.agent.state;

import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.RunStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 한 번의 질의 실행 상태. 단계 간에 넘기는 모든 값이 여기에 모인다.
 *
 * <p>LangGraph 의 State 에 해당하는 객체로, 워커는 이 컨텍스트만 읽고 고친다.
 * 실행이 끝나면 이 컨텍스트가 그대로 감사 로그(agent_run / agent_run_step)가 된다.</p>
 */
@Getter
@Setter
public class PipelineContext {

    private final String runId;
    private final String userId;
    private final String channel;
    private final String sessionId;
    private final String question;
    private final Instant startedAt = Instant.now();

    private List<ConversationTurn> history = List.of();
    private QueryPlan plan;
    private List<CacheCandidate> candidates = List.of();
    private ReuseDecision reuseDecision;
    private final Deque<LookupTask> pendingTasks = new ArrayDeque<>();
    private LookupTask currentTask;
    private PreparedSql preparedSql;
    private LookupResult lastLookup;
    private final List<LookupResult> lookups = new ArrayList<>();
    private final List<TraceStep> trace = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private AnswerOutcome answer;
    private RunStatus status = RunStatus.RUNNING;
    private int executionCount;
    private int extraRounds;
    private int cacheHitCount;
    private long durationMs;
    private String errorMessage;

    public PipelineContext(String runId, String userId, String channel, String sessionId, String question) {
        this.runId = runId;
        this.userId = userId;
        this.channel = channel;
        this.sessionId = sessionId;
        this.question = question;
    }

    public boolean hasPendingTask() {
        return !pendingTasks.isEmpty();
    }

    public LookupTask pollTask() {
        currentTask = pendingTasks.poll();
        return currentTask;
    }

    public void addLookup(LookupResult result) {
        lookups.add(result);
        lastLookup = result;
        executionCount++;
    }

    public boolean hasSuccessfulLookup() {
        return lookups.stream().anyMatch(r -> !r.failed());
    }

    public boolean hasFailedLookup() {
        return lookups.stream().anyMatch(LookupResult::failed);
    }

    public boolean cacheHit() {
        return cacheHitCount > 0;
    }

    public void noteError(String message) {
        errors.add(message);
    }
}
