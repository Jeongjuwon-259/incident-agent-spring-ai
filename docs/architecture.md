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

## 7. MCP·REST 어댑터 (테이블 기반 확장 + 양방향 Reshaping)

에이전트의 조회 대상은 내부 8대 테이블만이 아니다. 외부 MCP 도구와 REST API 도 **테이블에 등록된 룰**로
같은 방식(기준정보 → 가공 → 통신 → 정제 → 감사)으로 다룬다. 전송 프로토콜도 코드가 아니라
`transport_type` 값이 정한다.

```
POST /api/v1/mcp/execute/{apiId}
      │
      ▼
McpOrchestratorService.execute(apiId, params, runId)
      │
      ├─ [1] McpMasterRepository.findActive(apiId)   … svcm_tb_mcp_api_master 1행
      │                                                 (direction, transport_type, http_method, 룰)
      ├─ [2] INBOUND/BIDIRECTIONAL 이면
      │        McpSourceQueryReader.read(api)         … source_query (SELECT 전용)
      │        DataReshaper.reshape(request_rule)      … 로우 → {tool, arguments} 또는 REST JSON body
      │      OUTBOUND 이면 DataReshaper.reshape(request_rule) … 파라미터 정리
      │
      ├─ [3] transport_type 분기
      │        REST/REST_API  → RestApiClientGateway.call(api, payload, params)
      │                           … http_method, {pathVar}, Query String, headers_config, 인증
      │        MCP/HTTP/SSE   → McpClientGateway.call(api, payload)
      │                           … tools/call (JSON-RPC), JSON/SSE 해석, 인증
      │        └─ 연결 실패 + AUTO → McpFixtureCatalog (결정적 픽스처, REST 는 봉투 없이)
      │
      ├─ [4] DataReshaper.reshape(response_rule)       … Raw → 압축 JSON
      │
      ├─ [5] McpPayloadLogRepository.insert()          … raw/reshaped 4종 + transport_type/http_method + 상태·소요시간
      ▼
McpExecutionResult (status, transportType, httpMethod, request, response, stages, warnings, logId)
```

| 클래스 | 책임 | 하지 않는 것 |
|---|---|---|
| `McpOrchestratorService` | 5단계 흐름, 프로토콜 분기 1줄, 실패 시에도 로그 1행, 등록 검증 | JSONPath 해석, HTTP |
| `DataReshaper` | 룰 파싱·검증, 필드 매핑·필터·집계·변환, envelope(MCP_TOOL_CALL/REST_JSON) | 통신, DB |
| `McpClientGateway` | 요청 조립(JSON-RPC), SSE/JSON 해석, 인증 헤더, 픽스처 폴백 | 어떤 필드를 쓸지 |
| `RestApiClientGateway` | HTTP 메서드, 경로변수 치환, Query String, 커스텀 헤더, 픽스처 폴백 | 어떤 필드를 쓸지 |
| `McpAuthHeaders` | 환경변수 기반 인증 헤더 해석 (두 게이트웨이 공용) | 시크릿 저장 |
| `McpApiRegistrationValidator` | 등록 시점 검증 (전송/인증/헤더/룰/SSRF/원천 SELECT) | 통신, 실행 |
| `SourceQueryValidator` | 원천 SELECT 읽기 전용·단일문장 검증 | 변환, 실행 |
| `McpSourceQueryReader` | 원천 SELECT 실행·타입 정규화·행 수 상한 | 변환 |
| `McpMasterRepository` / `McpPayloadLogRepository` | 테이블 CRUD (로그는 마스킹 후 저장) | 판단 |

### 7.1 두 게이트웨이가 같고 다른 점

```
같은 것 : 입력 McpApiMaster + Reshaping 된 payload → 출력 McpCallResult(raw, payload, status, fallback)
다른 것 : 봉투 유무(JSON-RPC tools/call ↔ 순수 JSON), 메서드 고정(POST ↔ http_method),
          파라미터 전달(arguments ↔ 경로변수/Query String/본문), 헤더(headers_config)
```

출력 타입이 같으므로 응답 Reshaping·감사로그·픽스처 폴백은 한 벌로 유지된다. 프로토콜을 더할 때는
게이트웨이 하나 + `McpApiMaster.isRestTransport()` 자리의 분기 하나만 늘면 된다.

### 7.2 REST 전송 규칙 (요약)

| 항목 | 규칙 |
|---|---|
| 경로변수 | `{name}` ← params/Reshaping 결과 값, 경로 세그먼트 인코딩(공백 `%20`). 값이 없으면 502 |
| Query String | `GET`/`DELETE` 한정. 경로변수로 쓰인 키를 뺀 나머지. 배열은 `a,b,c` |
| 본문 | `POST`/`PUT`/`PATCH` 한정. Reshaping 결과 JSON (Content-Type: application/json) |
| 헤더 | `headers_config` → `auth_config`(BEARER/API_KEY/BASIC, 환경변수) 순. 인증이 같은 이름이면 이긴다 |
| 실패 | AUTO: 연결 실패만 픽스처 폴백 / 4xx·5xx 는 FAILED + `HTTP nnn` / HTTP 모드: 폴백 없음 |

### 7.3 룰이 코드보다 앞서는 지점

- 새 외부 시스템 연동: `svcm_tb_mcp_api_master` 에 행 1개 + 룰 2개. **배포 없음.**
- MCP ↔ REST 전환: `transport_type`·`http_method` 두 칸 수정. **재기동만.**
- 필드명 변경/노이즈 제거: `response_reshaping_rule` 수정. **재기동만.**
- 사고 조사: `svcm_tb_mcp_payload_log` 에서 `raw_payload_out` 과 `reshaped_payload_out` 을 나란히 비교.
  `transport_type`/`http_method` 로 어떤 경로로 나갔는지 먼저 확인한다.

### 7.4 실패 처리

| 실패 | 처리 |
|---|---|
| 룰 JSON/JSONPath 오류 | 실행 전 `ReshapingException` → 400 (필드명·경로 포함) |
| 필수 필드 원천 없음 | `ReshapingException` → 400, 감사 로그에 FAILED 1행 |
| 엔드포인트 연결 실패 (AUTO) | 픽스처 폴백, `status=FALLBACK`, 사유 `OFFLINE_FALLBACK …` |
| 엔드포인트 연결 실패 (HTTP) | `McpTransportException` → 502 |
| 경로변수 값 없음 (REST) | `McpTransportException` → 502 (빈 값으로 호출하지 않음) |
| HTTP 4xx/5xx | 폴백 없음. `status=FAILED`, `error_message=HTTP nnn`, 원문은 raw 로그 |
| 원천 SQL 오류 | 실행 중단 + FAILED 로그 (raw in 은 남지 않을 수 있음) |

## 8. 확장 지점

| 확장 | 방법 |
|---|---|
| 새 테이블/뷰 추가 | `schema-catalog.json` 에 추가 (프롬프트·가드·마스킹 자동 반영) |
| 새 조회 유형 | `Intent` 추가 + (LLM 사용 시) 프롬프트 규칙 + (오프라인) `HeuristicSqlTemplates` 분기 |
| Jira/메일/API 도구 | `svcm_tb_mcp_api_master` 에 등록 → `POST /api/v1/mcp/execute/{apiId}` (코드 수정 없음) |
| REST API 연동 | 위와 같은 테이블에 `transport_type=REST`, `http_method`, `headers_config` 만 지정 (코드 수정 없음) |
| 새 프로토콜 | `RestApiClientGateway` 처럼 `McpCallResult` 를 돌려주는 게이트웨이 1개 + Orchestrator 의 분기 1줄 |
| 벡터 저장소 | `SemanticSimilarity` 구현 교체 (pgvector/Redis/사내 RAGaaS) |
| 감사 저장소 | `AuditRepository` 를 JDBC→Kafka/OpenSearch 로 교체 |
| 인증/권한 | Controller 앞단 필터에서 userId/Scope 주입, `SqlGuard` 에 Scope 조건 추가 |
| 새 공통 검증 | `common/validation/ValidationUtils` 에 이름 있는 메서드 추가 (업무 소스에 if 문 금지) |
| 새 고정 상수 | `common/constant/*` 에 성격별로 추가하고 `application.yml`·README §10 동기화 |

## 9. 패키지 배치 규칙 (업무 소스 ↔ 패턴 소스 분리)

도메인 패키지 상위 레벨에는 **업무 흐름과 핵심 도메인 모델만** 둔다. 예외·오류 핸들러·저수준 어댑터·픽스처·리포지토리·보조 모델은 한 단계 아래 하위 패키지로 내린다.

| 성격 | 위치 | 예 |
|---|---|---|
| 업무 흐름 | `<도메인>/` | `state.IncidentQueryFlowService`, `mcp.McpOrchestratorService`, `web.*Controller` |
| 핵심 도메인·상태 모델 | `<도메인>/`, `model/` | `PipelineContext`, `PipelineResult`, `McpApiMaster`, `QueryPlan` |
| 단계별 처리 | `state/worker/` | `QueryUnderstandingWorker` … `AnswerGenerationWorker` |
| 단계 이력 | `state/trace/` | `TraceRecorder`, `TraceStep` |
| 공통 검증 | `common/validation/` | `ValidationUtils`, `ValidationException` |
| 공통 상수 | `common/constant/` | `AgentConstants`, `McpConstants`, `SecurityConstants`, `SqlConstants` |
| 예외 | `<도메인>/exception/` | `mcp/exception/*`, `llm/exception/LlmException`, `gateway/exception/SqlExecutionException` |
| 전역 오류 응답 | `web/handler/` | `GlobalExceptionHandler` |
| 통신 어댑터·인증 | `mcp/transport/` | `McpClientGateway`, `RestApiClientGateway`, `McpAuthHeaders` |
| 변환·룰 엔진 | `mcp/reshaping/`, `llm/prompt/`, `web/mapper/` | `DataReshaper`, `PromptBuilder`, `WebMapper` |
| 등록·입력 검증 | `mcp/validation/` | `McpApiRegistrationValidator`, `SourceQueryValidator` |
| SQL 검증·실행·마스킹 | `gateway/guard/`, `gateway/jdbc/`, `gateway/mask/` | `SqlGuard`, `DbExecutor`, `PiiMasker` |
| 로그 마스킹·외부 호출 정책 | `security/mask/`, `security/net/` | `SensitiveDataMasker`, `OutboundTargetPolicy` |
| 리포지토리·원천 조회 | `<도메인>/repository/` | `McpMasterRepository`, `VerifiedSqlRepository`, `AuditRepository` |
| 픽스처·시드 | `mcp/fixture/`, `config/seed/` | `McpFixtureCatalog`, `DemoDataSeeder` |
| 보조 모델·구현 | `nl2sql/metadata/`, `nl2sql/heuristic/`, `rag/similarity/`, `eval/metric/`, `llm/support/`, `llm/gateway/` | `TableMetadata`, `HeuristicSqlTemplates`, `SemanticSimilarity`, `ResultSetComparator`, `JsonSupport`, `SpringAiLlmGateway` |

패키지 분리는 읽기 순서를 위한 것이고 동작은 바꾸지 않는다. 컨트롤러 URL·DB 스키마·JSON 응답 규격·오류 코드는 이동 전과 동일하며, 이동으로 필요해진 클래스만 `public` 으로 열었다.

## 10. 보안 게이트 (어디서 무엇을 막는가)

| 시점 | 게이트 | 막는 것 | 실패 시 |
|---|---|---|---|
| 도구 등록 | `McpApiRegistrationValidator` | 미지원 전송/인증, 평문 시크릿(`auth_config` 값, 인증 헤더), 헤더 개행, 룰 문법, SSRF 대상, 쓰기 가능한 `source_query` | 400 (`OUTBOUND_TARGET_BLOCKED` 포함) |
| 실행 직전 | `OutboundTargetPolicy` | 등록 후 테이블이 바뀐 SSRF 대상, 스킴/사용자정보/리다이렉트 | `FAILED` + 감사 로그 1행 |
| SQL 준비 | `SqlGuard` | DML/DDL, 다중문장/주석, 허용목록 밖 테이블, 묵시적 조인, 무제한 조회 | 조회 `FAILED` (보안 위반은 재시도 없음) |
| 원천 조회 | `SourceQueryValidator` | 다중문장, 주석, 변경/실행 키워드, 무제한 행 | 등록 400 / 실행 `FAILED` |
| 결과 반환 | `PiiMasker` | 사용자 마스터 PII 컬럼 | 마스킹된 값 |
| 로그·감사 저장 | `SensitiveDataMasker` | 토큰·API Key·이메일·전화번호·주민번호 | `***` 로 치환 후 저장 |
| API 응답 | `WebMapper` | 저장된 로그/헤더의 시크릿 재노출 | `***` 로 치환 |

- 마스킹은 **자르기(truncate) 전에** 수행한다. 경계에서 잘린 값이 남지 않게 하기 위함이다.
- 등록 검증과 실행 검증을 **둘 다** 두는 이유: `svcm_tb_mcp_api_master` 는 API 밖(DB 콘솔)에서도 수정될 수 있다.
