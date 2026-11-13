package com.samsung.gsre.agent.gateway;

import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.llm.LlmGateway;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.SqlExecutionResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SQL 검증 → 실행 → 오류 피드백 기반 Self-Repair 루프.
 *
 * <h2>기준정보 (상단)</h2>
 * <ul>
 *   <li>재시도 횟수: {@code agent.pipeline.max-repair-attempts} (기본 2회)</li>
 *   <li>보안 위반(허용되지 않은 테이블, 다중 문장 등)은 재시도하지 않는다.</li>
 *   <li>문법/컬럼 오류만 오류 메시지를 LLM 에 피드백해 재생성한다.</li>
 * </ul>
 *
 * <h2>가공 흐름 (중단)</h2>
 * <ol>
 *   <li>SqlGuard 로 검증한다.</li>
 *   <li>통과하면 DbExecutor 로 실행한다.</li>
 *   <li>검증/실행 오류가 나면 오류 내용을 붙여 SQL 을 재생성하고 1번으로 돌아간다.</li>
 * </ol>
 *
 * <h2>결과 (하단)</h2>
 * {@link LookupResult} — 실행 SQL, 결과셋, 재시도 이력, 실패 사유.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfRepairEngine {

    private final SqlGuard sqlGuard;
    private final DbExecutor dbExecutor;
    private final LlmGateway llmGateway;
    private final AgentProperties props;

    public LookupResult execute(String question, LookupTask task, PreparedSql prepared,
                                List<CacheCandidate> similar) {
        PreparedSql attempt = prepared;
        List<String> repairNotes = new ArrayList<>();
        String lastError = null;
        int maxRepairs = props.getPipeline().getMaxRepairAttempts();

        for (int attemptNo = 0; attemptNo <= maxRepairs; attemptNo++) {
            GuardVerdict verdict = sqlGuard.validate(attempt.sql());

            if (verdict.valid()) {
                try {
                    SqlExecutionResult result = dbExecutor.execute(verdict, attempt.parameters());
                    if (attemptNo > 0) {
                        repairNotes.add(attemptNo + "회 수정 후 실행 성공");
                    }
                    return new LookupResult(task.id(), task.purpose(), verdict.sql(), attempt.parameters(),
                            result, attempt.origin(), repairNotes, false, null);
                } catch (SqlExecutionException e) {
                    lastError = e.getMessage();
                    log.warn("[repair] 실행 오류(attempt={}): {}", attemptNo + 1, lastError);
                }
            } else {
                lastError = String.join(" | ", verdict.violations());
                if (!verdict.repairable()) {
                    repairNotes.add("보안 정책 위반으로 재시도하지 않습니다: " + lastError);
                    return LookupResult.failed(task.id(), task.purpose(), attempt.sql(), attempt.parameters(),
                            attempt.origin(), repairNotes, lastError);
                }
                log.warn("[repair] 검증 실패(attempt={}): {}", attemptNo + 1, lastError);
            }

            if (attemptNo == maxRepairs) {
                break;
            }
            repairNotes.add((attemptNo + 1) + "차 오류: " + lastError);
            try {
                PreparedSql repaired = llmGateway.repairSql(question, task, attempt, lastError, similar);
                if (repaired == null) {
                    repairNotes.add("자동 수정이 불가능한 오류입니다.");
                    break;
                }
                attempt = repaired;
            } catch (RuntimeException e) {
                repairNotes.add("SQL 재생성 실패: " + e.getMessage());
                break;
            }
        }

        return LookupResult.failed(task.id(), task.purpose(), attempt.sql(), attempt.parameters(),
                attempt.origin(), repairNotes, lastError == null ? "SQL 실행에 실패했습니다." : lastError);
    }
}
