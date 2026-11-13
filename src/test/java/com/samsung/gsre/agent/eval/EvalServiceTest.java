package com.samsung.gsre.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 품질 평가 하네스 통합 테스트.
 *
 * <p>골든셋 3건을 비동기 배치로 평가해 4개 지표(EX/AA/FN/실행 성공)가 모두 Pass 인지 확인하고,
 * 같은 TC 를 다시 평가했을 때 과거 결과가 덮어써지지 않고 누적되는지 검증한다.</p>
 */
@SpringBootTest(properties = {
        "agent.llm.mode=offline",
        "agent.rag.embedding-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:evaltest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class EvalServiceTest {

    @Autowired
    private EvalService evalService;

    @Test
    @DisplayName("배치를 실행하면 지표별/종합 결과와 통과율이 저장된다")
    void runsBatchAndComputesPassRate() {
        EvalBatchStart start = evalService.startBatch(List.of("TC-001", "TC-002", "TC-004"), false, "tester");
        EvalBatch batch = await(start.batchId(), 120_000);

        assertThat(batch.status()).isEqualTo(EvalService.STATUS_COMPLETED);
        assertThat(batch.totalCount()).isEqualTo(3);
        assertThat(batch.completedCount()).isEqualTo(3);
        assertThat(batch.passedCount()).isEqualTo(3);
        assertThat(batch.passRate()).isEqualTo(100.0);

        List<EvalCaseResult> results = evalService.results(start.batchId());
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(EvalCaseResult::overallPass);
        assertThat(results).allMatch(EvalCaseResult::exPass);
        assertThat(results).allMatch(EvalCaseResult::aaPass);
        assertThat(results).allMatch(EvalCaseResult::fnPass);
        assertThat(results).allMatch(EvalCaseResult::execPass);
        assertThat(results).allMatch(r -> r.agentRunId() != null && r.questionSnapshot() != null);
    }

    @Test
    @DisplayName("같은 TC 를 다시 평가해도 이력이 누적된다")
    void keepsEvaluationHistory() {
        EvalBatchStart first = evalService.startBatch(List.of("TC-003"), false, "tester");
        await(first.batchId(), 60_000);
        EvalBatchStart second = evalService.startBatch(List.of("TC-003"), false, "tester");
        await(second.batchId(), 60_000);

        List<EvalCaseResult> history = evalService.caseHistory("TC-003", 10);
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
        assertThat(history).extracting(EvalCaseResult::batchId)
                .contains(first.batchId(), second.batchId());
    }

    private EvalBatch await(String batchId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            EvalBatch batch = evalService.batch(batchId);
            if (!EvalService.STATUS_RUNNING.equals(batch.status())) {
                return batch;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return evalService.batch(batchId);
    }
}
