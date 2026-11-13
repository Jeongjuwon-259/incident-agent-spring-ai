package com.samsung.gsre.agent.llm;

import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import java.util.List;

/**
 * LLM 이 필요한 판단만 모아 둔 경계.
 *
 * <p>구현체는 두 가지다.
 * <ul>
 *   <li>{@code SpringAiLlmGateway}: Spring AI ChatClient 로 로컬 LLM 호출 (기본)</li>
 *   <li>{@code HeuristicLlmGateway}: 모델 없이 규칙으로 동작하는 결정적 대체 구현 (데모·테스트)</li>
 * </ul>
 * SQL 검증·실행·분기처럼 코드로 확정할 수 있는 일은 이 경계 밖(일반 코드)에서 처리한다.</p>
 */
public interface LlmGateway {

    String provider();

    /** 1단계: 질의 이해 + 조회 계획 */
    QueryPlan understand(String question, List<ConversationTurn> history);

    /** 3단계: 검증 SQL 재사용 판단 */
    ReuseDecision judgeReuse(String question, QueryPlan plan, LookupTask task, List<CacheCandidate> candidates);

    /** 4단계: 신규 SQL 생성 */
    PreparedSql generateSql(String question, QueryPlan plan, LookupTask task, List<CacheCandidate> similar);

    /** 5단계: Self-Repair 재생성 */
    PreparedSql repairSql(String question, LookupTask task, PreparedSql failed, String errorMessage,
                          List<CacheCandidate> similar);

    /** 6단계: 결과 충분성 판단 + 최종 답변 생성 (LLM 1회) */
    AnswerOutcome composeAnswer(String question, List<LookupTask> plannedTasks,
                                List<LookupResult> results, List<ConversationTurn> history);

    /** 품질 평가 AA */
    JudgeVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer);

    /** 품질 평가 FN */
    JudgeVerdict judgeFaithfulness(String question, String resultContext, String answer);
}
