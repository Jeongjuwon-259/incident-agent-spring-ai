# 아키텍처 노트

이 문서는 `IncidentQueryFlowService` 를 중심으로 "요청이 어떤 순서로 흐르고, 어디서 LLM 을 쓰고, 어디서 코드로 확정하는가" 를 정리한다.

## 1. 요청 생명주기

```
HTTP POST /api/v1/agent/query
      │  QueryRequest (userId, channel, sessionId, question)
      ▼
AgentController ──► IncidentQueryFlowService.handle(AgentQueryCommand)
      │
      ├─ AuditRepository.startRun(ctx)          … agent_run 1행
      ├─ AgentRunScope.open(runId, maxLlmCalls)  … 실행 단위 LLM 예산
      │
      ├─ [1] QueryUnderstandingWorker   ──► LlmGateway.understand
      ├─ [2] RagCacheSearchWorker       ──► VerifiedSqlCacheService.search
      ├─ [3] ReuseDecisionWorker        ──► LlmGateway.judgeReuse
      ├─ [4] SqlCompileWorker           ──► LlmGateway.generateSql (또는 캐시 SQL)
      ├─ [5] SqlExecutionWorker         ──► SelfRepairEngine (SqlGuard → DbExecutor → repair)
      ├─ [6] AnswerGenerationWorker     ──► LlmGateway.composeAnswer
      │        ▲                     │
      │        └── 부족하면 [2]로 ────┘
      │
      ├─ AuditRepository.finishRun(ctx)          … 상태·답변·호출수·소요시간
      ├─ ConversationStore.append(user/assistant) … 멀티턴
      ▼
PipelineResult (runId, status, answer, lookups, trace, llmCalls)
```

## 2. 중심 흐름 코드 (축약)

```java
public PipelineResult handle(AgentQueryCommand command) {
    PipelineContext ctx = start(command);
    auditRepository.startRun(ctx);

    try (AgentRunScope scope = AgentRunScope.open(ctx.getRunId(), maxLlmCalls)) {
        // 1) 질의 이해
        queryUnderstandingWorker.understand(ctx);

        while (iterations++ < MAX_FLOW_ITERATIONS) {
            // 2~5) 계획된 조회마다 반복
            while (ctx.hasPendingTask() && ctx.getExecutionCount() < maxExecutions) {
                ragCacheSearchWorker.search(ctx);   // 2
                reuseDecisionWorker.decide(ctx);    // 3
                sqlCompileWorker.compile(ctx);      // 4
                sqlExecutionWorker.execute(ctx);    // 5
            }
            // 6) 충분성 판단 + 답변 (LLM 1회)
            answerGenerationWorker.answer(ctx);
            if (!needsAnotherRound(ctx)) break;
            queryUnderstandingWorker.replan(ctx);   // next_purposes → 새 작업
        }
    }
    ...
}
```

읽는 순서가 곧 업무 순서다. 세부 구현(프롬프트·파싱·JDBC)은 이 메서드에 없다.

## 3. 단계별 책임과 입출력

| 단계 | Worker | 입력 | 출력 | LLM | 코드로 확정하는 것 |
|---|---|---|---|---|---|
| 1 | `QueryUnderstandingWorker` | 질의, 최근 6턴 | `QueryPlan`(의도, APP, 작업 목록) | 1회 | 작업 수 상한, 빈 계획 대체 |
| 2 | `RagCacheSearchWorker` | 작업 purpose/filters | `List<CacheCandidate>` | 0 | 유사도 계산·정렬·Top-K |
| 3 | `ReuseDecisionWorker` | 후보, 작업 | `ReuseDecision` | 0~1회 (후보 없으면 생략) | 캐시 사용 횟수 집계 |
| 4 | `SqlCompileWorker` | 결정, 후보, 메타데이터 | `PreparedSql`(SQL, 파라미터, 출처) | 재사용 시 0 / 생성 시 1 | 파라미터 병합 우선순위 |
| 5 | `SqlExecutionWorker` → `SelfRepairEngine` | `PreparedSql` | `LookupResult`(결과, 복구 이력) | 오류 시 0~2회 | 안전 검증, 실행, 재시도 상한, 중복 조회 생략 |
| 6 | `AnswerGenerationWorker` | 계획, 누적 결과 | `AnswerOutcome`(충분성, 답변, 부족 항목) | 1회 | 다음 라운드 여부, 종료 조건 |

## 4. 상태 객체

`PipelineContext` 하나가 실행 전체를 들고 있다.

```
runId / userId / channel / sessionId / question / startedAt
history          ← 멀티턴
plan             ← 1단계 산출
candidates       ← 2단계 산출
reuseDecision    ← 3단계 산출
pendingTasks     ← 실행 큐 (T1, T2, R1 ...)
currentTask      ← 지금 처리 중인 작업
preparedSql      ← 4단계 산출
lookups          ← 5단계 누적 결과
answer           ← 6단계 산출
trace            ← 단계별 이력
status / durationMs / errorMessage / counters
```

워커는 이 객체만 읽고 고친다. 실행이 끝나면 그대로 감사 로그가 된다.

## 5. LLM 호출 지점과 예산

```
질의 1건 기준 최소: 2회 (계획 1 + 답변 1)
작업 1건당 추가: 재사용 판단 1 + NL2SQL 1 (캐시 재사용 시 0)
Self-Repair: 오류당 1 (최대 2)
상한: agent.pipeline.max-llm-calls (기본 24)
```

- 후보가 없으면 재사용 판단 LLM 을 호출하지 않는다.
- 계획된 작업이 남아 있으면 답변 생성을 미룬다.
- 예산 초과 시 `LlmBudgetExceededException` → 남은 결과로 답변 종료(FAILED/PARTIAL).
- 호출마다 `agent_llm_call` 에 purpose(`UNDERSTAND`, `REUSE_DECISION`, `NL2SQL`, `SELF_REPAIR`, `ANSWER`, `JUDGE_AA`, `JUDGE_FN`)와 지연·글자 수·추정 토큰이 남는다.

## 6. 실패 처리 원칙

| 실패 | 처리 |
|---|---|
| 로컬 LLM 연결 불가 | run FAILED + 조치 안내 문구 (base-url, offline 모드) |
| LLM JSON 파싱 실패 | 같은 단계 규칙 기반 폴백 + 경고 로그 (파이프라인 계속) |
| SQL 안전 위반(보안) | 해당 조회 FAILED, 재시도 없음 |
| SQL 문법/컬럼 오류 | Self-Repair 재생성 (최대 2회) |
| 최종 실패 | 답변 생성이 실패 사실을 밝히고, run 은 PARTIAL/FAILED |
| 실행 상한 도달 | 남은 작업 건너뛰고 답변 (경고 로그·trace) |

## 7. 확장 지점

| 확장 | 방법 |
|---|---|
| 새 테이블/뷰 추가 | `schema-catalog.json` 에 추가 (프롬프트·가드·마스킹 자동 반영) |
| 새 조회 유형 | `Intent` 추가 + (LLM 사용 시) 프롬프트 규칙 + (오프라인) `HeuristicSqlTemplates` 분기 |
| Jira/메일/API 도구 | `LlmGateway` 옆에 Tool Worker 추가, `PipelineContext` 에 결과 누적 |
| 벡터 저장소 | `SemanticSimilarity` 구현 교체 (pgvector/Redis/사내 RAGaaS) |
| 감사 저장소 | `AuditRepository` 를 JDBC→Kafka/OpenSearch 로 교체 |
| 인증/권한 | Controller 앞단 필터에서 userId/Scope 주입, `SqlGuard` 에 Scope 조건 추가 |
