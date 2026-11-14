package com.samsung.gsre.agent.llm.gateway;

import com.samsung.gsre.agent.eval.metric.HeuristicJudge;
import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicAnswerComposer;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicQueryPlanner;
import com.samsung.gsre.agent.nl2sql.heuristic.HeuristicSqlTemplates;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모델 없이 동작하는 결정적 LLM 대체 구현 ({@code agent.llm.mode=offline}).
 *
 * <p>로컬 LLM 이 없는 환경에서도 6단계 파이프라인·감사 로그·품질 평가 하네스를 끝까지 시험할 수 있게 한다.
 * 실제 품질(NL2SQL 일반화, 답변 문장력)은 로컬 LLM 모드에서 나오며, 이 구현은 회귀 테스트의 기준선이다.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.llm.mode", havingValue = "offline")
public class HeuristicLlmGateway implements LlmGateway {

    private final HeuristicQueryPlanner planner;
    private final HeuristicSqlTemplates sqlTemplates;
    private final HeuristicAnswerComposer answerComposer;
    private final HeuristicJudge judge;

    @Override
    public String provider() {
        return "offline-heuristic";
    }

    @Override
    public QueryPlan understand(String question, List<ConversationTurn> history) {
        return planner.plan(question, history);
    }

    @Override
    public ReuseDecision judgeReuse(String question, QueryPlan plan, LookupTask task,
                                    List<CacheCandidate> candidates) {
        return planner.decideReuse(task, candidates);
    }

    @Override
    public PreparedSql generateSql(String question, QueryPlan plan, LookupTask task,
                                   List<CacheCandidate> similar) {
        return sqlTemplates.build(question, plan, task);
    }

    @Override
    public PreparedSql repairSql(String question, LookupTask task, PreparedSql failed, String errorMessage,
                                 List<CacheCandidate> similar) {
        return sqlTemplates.repair(failed, errorMessage);
    }

    @Override
    public AnswerOutcome composeAnswer(String question, List<LookupTask> plannedTasks,
                                       List<LookupResult> results, List<ConversationTurn> history) {
        return answerComposer.compose(question, results);
    }

    @Override
    public JudgeVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
        return judge.answer(question, expectedAnswer, actualAnswer);
    }

    @Override
    public JudgeVerdict judgeFaithfulness(String question, String resultContext, String answer) {
        return judge.faithfulness(resultContext, answer);
    }
}
