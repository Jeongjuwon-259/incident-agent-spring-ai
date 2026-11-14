# 코드 구성 규칙과 인수인계서 (CONVENTIONS & HANDOFF)

> **이 문서의 목적**: 이 저장소를 이어받는 **다른 LLM 모델(Claude, GPT, Gemini, 로컬 LLM)과 개발자**가
> 같은 규칙으로 코드를 쓰고, 같은 방식으로 검증하고, 같은 자리에 파일을 두게 하는 것.
>
> **읽는 순서**: `AGENTS.md`(요약) → 이 문서(상세) → `docs/architecture.md`(구조) → `README.md` §18(요약·링크).
> 규칙이 충돌하면 **이 문서 → AGENTS.md → README** 순으로 우선한다.
>
> 최종 갱신: 2026-11-14 (전체 소스 재검수 + 보안 보완 + 공통화 + 인수인계 문서화)

---

## 0. 한눈에 보는 구조

```
[Web]        Controller ──► DTO/Mapper
                 │
[Flow]       IncidentQueryFlowService / McpOrchestratorService / EvalService
                 │  (시나리오만: 순서·분기·상한)
                 ▼
[Step]       Worker (state/worker) · Reshaper · Gateway · Validator
                 │  (구현: 프롬프트, JSONPath, HTTP, 검증)
                 ▼
[Adapter]    Repository (JdbcTemplate) · LlmGateway (Spring AI / 규칙)
                 │
[Data]       H2/MySQL · svcm_tb_* · agent_* · svcm_tb_mcp_*
```

| 계층 | 책임 | 하지 않는 것 | 대표 클래스 |
|---|---|---|---|
| Web | HTTP 계약, 상태 코드 | SQL·HTTP·도메인 판단 | `AgentController`, `WebMapper` |
| Flow | "무엇을 어떤 순서로" | 세부 가공·파싱 | `IncidentQueryFlowService`, `McpOrchestratorService`, `EvalService` |
| Step | 단계별 구현 | 전체 흐름 결정 | `*Worker`, `DataReshaper`, `RestApiClientGateway` |
| Adapter | DB·LLM·외부 통신 | 비즈니스 분기 | `*Repository`, `LlmGateway` 구현 |
| Common | 검증·상수 공통화 | 도메인 상태 보관 | `ValidationUtils`, `McpConstants` |

---

## 1. 5대 규칙 (위반 시 머지 금지)

### 규칙 1 — MVC 친화적, 플로우와 구현의 분리
- `Controller` / `FlowService` / `Orchestrator` 는 **시나리오**만 담는다.
- SQL, HTTP 요청 조립, JSON 파싱, 문자열 포매팅, 널 체크 더미는 `Worker`/`Gateway`/`Reshaper`/`Repository`/`Mapper`/`ValidationUtils` 로 내린다.
- **자가 점검**: 흐름 클래스에서 `jdbc`, `HttpRequest`, `JsonPath`, `new BigDecimal`, `substring` 이 보이면 잘못된 것이다.

```java
// IncidentQueryFlowService.handle — 좋은 예: 읽으면 곧 시나리오
queryUnderstandingWorker.understand(ctx);
while (iterations++ < MAX_FLOW_ITERATIONS) {
    executePendingLookups(ctx);      // 2~5단계 (RAG → 판단 → SQL → 실행)
    answerGenerationWorker.answer(ctx);
    if (!needsAnotherRound(ctx)) break;
    queryUnderstandingWorker.replan(ctx);
}
```

### 규칙 2 — 컨설팅형 IPO (Input → Process → Output)
- 모든 주요 업무 클래스는 **상단(기준정보) → 중단(가공 흐름) → 하단(결과)**.
- 상수·패턴·임계치·의존성은 **클래스 최상단**. 메서드 사이에 상수를 선언하지 않는다.
- 여러 처리 메서드는 **실행 순서대로** 배치한다.
- 결과 조립·저장 형식·로그 유틸은 **맨 아래**.

```java
// ==================================================================
// 상단 (INPUT) — 기준정보: 상수·임계치·의존성
// ==================================================================
private static final int MAX_FLOW_ITERATIONS = AgentConstants.DEFAULT_MAX_FLOW_ITERATIONS;
private final QueryUnderstandingWorker queryUnderstandingWorker;

// ==================================================================
// 중단 (PROCESS) — 6단계 처리 흐름 (읽는 순서 = 실행 순서)
// ==================================================================
public PipelineResult handle(AgentQueryCommand command) { ... }

// ==================================================================
// 하단 (OUTPUT) — 결과 상태·종료 처리
// ==================================================================
private void decideStatus(PipelineContext ctx) { ... }
```

- **예외**: `PromptBuilder` 처럼 "프롬프트 생성(INPUT) + 응답 파싱(OUTPUT)" 쌍이 단계별로 반복되는 코덱 클래스는
  **단계 순서(1→3→4→6→평가)** 를 우선한다. 이 경우 클래스 상단 주석에 그 이유를 적는다.

### 규칙 3 — 패키지 분리 (업무 소스 vs 패턴 소스)
- 도메인 상위 레벨 = **업무 흐름 + 핵심 도메인 모델**.
- 예외·핸들러·저수준 어댑터·리포지토리·픽스처·검증기·마스커·보조 모델 = **한 단계 아래 하위 패키지**.
- 이동 리팩토링은 **공개 API(URL·DB 스키마·JSON·오류 코드)를 바꾸지 않는다.**
  `git mv` → 패키지 선언 → import → 테스트 → 문서 순서로 하고, 필요할 때만 `public` 을 연다.

### 규칙 4 — 테이블 기반 REST/MCP 확장 + 보안
- 새 도구는 `svcm_tb_mcp_api_master` **행 1개**로 추가한다. 코드 분기·배포 없음.
- 보안 규칙(§6) 위반은 **거부**한다. 예외를 만들어 우회하지 않는다.

### 규칙 5 — 검증·고정 상수는 `common` 으로 공통화
- 반복되는 **널/공백/필수 엔터티 존재/범위/형식** 검증은 `common/validation/ValidationUtils`.
- **타임아웃 기본값·임계치·프로토콜/모드/헤더명·기본 포맷·허용 목록**은 `common/constant/*`.
- 업무 소스에 `if (x == null || x.isBlank()) throw ...` 를 새로 쓰지 않는다.
- 같은 문자열/숫자를 두 클래스에 적지 않는다.

---

## 2. `common` 패키지 상세

### 2.1 `common/validation/ValidationUtils`

| 메서드 | 의미 | 실패 시 |
|---|---|---|
| `isBlank(v)` / `hasText(v)` | 널·공백 판정 | — |
| `requireText(v, field)` | 필수 텍스트 | `ValidationException("<field> 값이 필요합니다.")` |
| `requireText(v, field, message)` | 메시지 지정 필수 텍스트 | `ValidationException(message)` |
| `requirePresent(v, message)` | 엔터티/객체 존재 | `ValidationException(message)` |
| `requireFound(Optional<T>, message)` | 조회 결과 존재 | `ValidationException(message)` |
| `requireTrue(condition, message)` | 중복·정책 조건 | `ValidationException(message)` |
| `requireOneOf(v, allowedSet, message)` | 허용 목록(전송/인증) | `ValidationException(message)` |
| `requirePattern(v, pattern, message)` | 환경변수/헤더 이름 형식 | `ValidationException(message)` |
| `requireMaxLength(v, max, message)` | 길이 상한 | `ValidationException(message)` |
| `requireRange(v, min, max, message)` / `requirePositive` / `requireNonNegative` | 범위 | `ValidationException(message)` |
| `orDefault(v, fallback)` / `firstNonBlank(...)` | 기본값 정규화 | 실패 아님 |

- `ValidationException extends IllegalArgumentException` → 기존 `GlobalExceptionHandler` 가 **400 BAD_REQUEST** 로 변환한다.
- **도메인 의미가 강한 실패는 도메인 예외를 유지**한다:
  - 등록되지 않은 MCP API → `McpApiNotFoundException` (404)
  - SSRF 정책 위반 → `OutboundTargetBlockedException` (400, code `OUTBOUND_TARGET_BLOCKED`)

```java
// good — 검증 의도가 드러나고 예외 변환이 공통화됨
ValidationUtils.requireText(api.apiId(), "apiId");
ValidationUtils.requireOneOf(transport, McpConstants.TRANSPORT_TYPES, "지원하지 않는 transport_type: " + transport);
```

### 2.2 `common/constant/*`

| 클래스 | 담는 값 | 사용처 |
|---|---|---|
| `AgentConstants` | ID 형식/접두어, 파이프라인·RAG·가드·평가·LLM·MCP 기본값, 페이지 크기, 로그 길이 | `AgentProperties`, `IncidentQueryFlowService`, `EvalService`, `McpExecutionContext`, `WebMapper`, `AuditRepository` |
| `McpConstants` | direction/transport/auth/mode 문자열, HTTP 메서드·헤더명, 활성 플래그, 로그 ID/조회 상한 | `McpApiMaster`, `McpAuthHeaders`, 게이트웨이, 검증기, 리포지토리 |
| `SecurityConstants` | 마스킹 토큰 `***`, 시크릿 키 목록, `auth_config` 금지 키, 메타데이터 주소 | `SensitiveDataMasker`, `OutboundTargetPolicy`, `McpApiRegistrationValidator` |
| `SqlConstants` | 파괴적 SQL 키워드, 시스템 스키마, FROM 종료 키워드, 사용자 마스터 테이블/정책값 | `SqlGuard`, `SourceQueryValidator`, `AgentProperties` |

- 상수를 추가하면: (1) `application.yml` 기본값, (2) README §10 설정표, (3) 이 문서의 표를 함께 갱신한다.
- **상수 클래스에는 로직을 넣지 않는다.** 패턴 조립·비교는 사용하는 클래스에서 한다.

---

## 3. 패키지 배치 결정표

| 성격 | 위치 | 현재 예 |
|---|---|---|
| 공통 검증 | `common/validation/` | `ValidationUtils`, `ValidationException` |
| 공통 상수 | `common/constant/` | `AgentConstants`, `McpConstants`, `SecurityConstants`, `SqlConstants` |
| 업무 흐름 | `<도메인>/` | `state.IncidentQueryFlowService`, `mcp.McpOrchestratorService`, `eval.EvalService` |
| 핵심 모델 | `<도메인>/`, `model/` | `PipelineContext`, `McpApiMaster`, `QueryPlan` |
| 단계 처리 | `state/worker/` | `QueryUnderstandingWorker` … `AnswerGenerationWorker` |
| 단계 이력 | `state/trace/` | `TraceRecorder`, `TraceStep` |
| 예외 | `<도메인>/exception/` | `mcp/exception/*`, `llm/exception/LlmException`, `gateway/exception/SqlExecutionException` |
| 전역 오류 응답 | `web/handler/` | `GlobalExceptionHandler` |
| HTTP 어댑터·인증 | `mcp/transport/` | `McpClientGateway`, `RestApiClientGateway`, `McpAuthHeaders` |
| 변환 엔진 | `mcp/reshaping/` | `DataReshaper`, `ReshapingRule` |
| 리포지토리·원천 조회 | `<도메인>/repository/` | `McpMasterRepository`, `McpSourceQueryReader` |
| 등록/입력 검증 | `<도메인>/validation/` | `McpApiRegistrationValidator`, `SourceQueryValidator` |
| SQL 검증 | `gateway/guard/` | `SqlGuard`, `GuardVerdict` |
| JDBC 실행 | `gateway/jdbc/` | `DbExecutor` |
| PII 마스킹 | `gateway/mask/` | `PiiMasker` |
| 시크릿/PII 마스킹 | `security/mask/` | `SensitiveDataMasker` |
| 외부 호출 정책 | `security/net/` | `OutboundTargetPolicy` |
| 픽스처·시드 | `mcp/fixture/`, `config/seed/` | `McpFixtureCatalog`, `DemoDataSeeder` |
| 규칙 기반 폴백 | `nl2sql/heuristic/` | `HeuristicQueryPlanner/SqlTemplates/AnswerComposer` |
| 보조 모델·구현 | `nl2sql/metadata/`, `rag/similarity/`, `eval/metric/`, `llm/support/`, `llm/gateway/`, `llm/prompt/` | `TableMetadata`, `SemanticSimilarity`, `JsonSupport` |

**금지**
- `gateway/` 상위에 검증기·유틸 다시 쌓기 (guard/jdbc/mask 하위 사용)
- `mcp/` 상위에 새 어댑터·리포지토리 추가
- `web/` 에 도메인 로직·SQL·HTTP 구현
- `model/` 에 특정 도메인 전용 로직 (공유 커널만)

---

## 4. 테이블 기반 REST/MCP 확장 (코드 수정 없이)

### 4.1 값이 전송 방식을 정한다

| `direction` | 내부 원천 SELECT | 요청 변환 | 응답 정제 |
|---|---|---|---|
| `OUTBOUND` | 없음 | 호출 파라미터 → 룰 | Raw → 룰 |
| `INBOUND` | `source_query` 실행 | DB 로우 → 룰(MCP/REST body) | Raw → 룰 |
| `BIDIRECTIONAL` | `source_query` 실행 | DB 로우 → 룰 | Raw → 룰 |

| `transport_type` | 게이트웨이 | 봉투 | 메서드 |
|---|---|---|---|
| `MCP` / `HTTP` / `SSE` | `McpClientGateway` | JSON-RPC `tools/call` | POST 고정 |
| `REST` / `REST_API` | `RestApiClientGateway` | 없음(순수 JSON) | `http_method` (GET/POST/PUT/PATCH/DELETE) |

### 4.2 등록 절차

1. 행 추가 (SQL 또는 `POST /api/v1/mcp/apis`).
2. 필수 값: `api_id`, `tool_name`, `direction`, `transport_type`, `endpoint_url`.
3. 인증: `auth_type` + `auth_config` — **환경변수 이름만**
   ```json
   {"envVar":"MCP_K8S_TOKEN","header":"Authorization"}
   ```
4. 헤더: `headers_config` — 비밀이 아닌 헤더만. 인증 헤더·개행 금지.
5. 룰: `request_reshaping_rule`, `response_reshaping_rule` (JSONPath).
6. INBOUND/BIDIRECTIONAL 이면 `source_query` — **읽기 전용 단일 SELECT**.
7. 실행: `POST /api/v1/mcp/execute/{apiId}` → `status`, `request`, `response`, `stages`, `logId`.
8. 확인: `GET /api/v1/mcp/logs?apiId=...` (마스킹된 raw ↔ reshaped).

### 4.3 룰 문법 요약

```json
{
  "version": "1.0",
  "mode": "OBJECT | COLLECTION",
  "rootPath": "$.data",
  "itemPath": "$.items[*]",
  "filter": [{"path": "$.status.phase", "op": "NE", "value": "Succeeded"}],
  "limit": 10,
  "dropNull": true,
  "fields": [
    {"target": "podName", "source": "$.metadata.name", "required": true},
    {"target": "restarts", "source": "$.status.containerStatuses[*].restartCount", "aggregate": "SUM", "default": 0},
    {"target": "severity", "constant": "HIGH"},
    {"target": "image", "source": "$.spec.containers[0].image", "transform": "TO_STRING"}
  ],
  "envelope": {"type": "MCP_TOOL_CALL | REST_JSON", "tool": "...", "argumentsKey": "entries", "payloadKey": "incidents",
               "attributes": {"source": "gsre-agent"}}
}
```
- `transform`: `NONE|TRIM|UPPER|LOWER|TO_STRING|TO_NUMBER|TO_INT|TO_BOOLEAN`
- `aggregate`: `SUM|COUNT|AVG|MIN|MAX`
- `filter.op`: `EXISTS|EQ|NE|IN|CONTAINS|GT|LT`
- 룰 오류는 등록 시점에 400으로 거부된다(`McpApiRegistrationValidator`).

### 4.4 새 프로토콜이 정말 필요할 때
`McpCallResult` 를 돌려주는 게이트웨이 1개 + `McpOrchestratorService.call()` 의 분기 1줄 + `McpConstants.TRANSPORT_*`.
그 외에는 **테이블 행 추가만** 한다.

---

## 5. 보안 규칙 (상세)

### 5.1 시크릿
- `auth_config`: `envVar` 참조만. `value/secret/token/password/apikey/credential` 키는 등록 거부.
- `headers_config`: `Authorization`, `X-Api-Key`, `Cookie`, `*token*`, `*secret*` 등은 등록 거부.
- 코드·로그·응답에 시크릿 평문 금지. 환경변수 부재 시 헤더만 생략(로컬 데모).

### 5.2 마스킹 (`SensitiveDataMasker`)
- 대상: `svcm_tb_mcp_payload_log` 4개 payload, `agent_llm_call.prompt/response/error`, `agent_run`(질문/답변/오류),
  `agent_run_step.detail`, 애플리케이션 로그의 URL/오류.
- 규칙: 시크릿 키 값 전체 `***`, `Bearer/Basic` 토큰, 이메일 `ki***@domain`, 전화 `010-****-5678`, 주민번호.
- **자르기 전에 마스킹**한다. API 로 payload 를 내보낼 때도 `mask(...)` 를 거친다.
- `PiiMasker`(조회 결과)와 `SensitiveDataMasker`(로그)는 목적이 다르다. 둘 다 유지한다.

### 5.3 SSRF (`OutboundTargetPolicy`)
- `http/https` 만, URL 사용자정보 금지, 리다이렉트 금지(`HttpClient.Redirect.NEVER`).
- 항상 차단: 링크로컬·멀티캐스트·미지정·메타데이터(169.254.169.254, metadata.google.internal, 100.100.100.200 등).
- `allow-local-endpoints=false` 면 루프백·사설(RFC1918)·IPv6 ULA 차단.
- `allowed-hosts` 가 있으면 그 호스트/서브도메인만.
- **등록 시점 + 전송 직전 2회** 검증한다(테이블은 API 밖에서 수정될 수 있다).
- 경로변수 템플릿(`{appId}`)은 "x" 로 치환해 문법만 본다.

### 5.4 SQL
`SqlGuard`
- SELECT/WITH 단일 문장, 주석·다중 문장 금지
- 허용 테이블 = `schema-catalog.json` (CTE 이름 제외)
- `FROM a, b` 묵시적 조인 금지 (두 번째 테이블 허용목록 우회 방지)
- `INSERT/UPDATE/DELETE/MERGE/DROP/ALTER/CREATE/TRUNCATE/GRANT/REVOKE/CALL/EXEC/EXECUTE/REPLACE/INTO/RUNSCRIPT/SHUTDOWN/BACKUP/LOAD/HANDLER/RENAME/UNLOCK/LOCK` 금지
- 시스템 스키마(`information_schema`, `sys`, `pg_catalog`, `mysql`, `performance_schema`) 금지
- LIMIT 강제(`max-rows`), 사용자 마스터 `SELECT *` 금지, PII 마스킹 플래그

`SourceQueryValidator`
- 읽기 전용 단일 SELECT/WITH, 주석·세미콜론 금지, 동일 파괴 키워드 금지, 행 수 상한(`LIMIT max+1`)

`DbExecutor`
- named parameter만, query timeout, max rows, PII 마스킹, 결과 타입 정규화

### 5.5 헤더·전송
- 헤더 이름 형식 검증, 값의 CR/LF 금지, 인증 헤더는 `auth_config` 로만.
- 타임아웃 필수. AUTO 폴백은 **연결 실패에만**, 4xx/5xx 는 FAILED 로 남긴다.

### 5.6 노출 경계
- 이 API 에는 자체 인증이 없다. **사내 게이트웨이/프록시 뒤에 두고 외부에 직접 노출하지 않는다.**
  인증·권한은 상위 계층에서 주입하고 `userId`/Scope 를 넘긴다.
- H2 콘솔은 로컬 데모용(`web-allow-others: false`). 운영에서는 비활성화한다.

---

## 6. LLM 경계 규칙

- 모든 LLM 호출은 `LlmGateway` 구현(`SpringAiLlmGateway` / `HeuristicLlmGateway`) 뒤에서만.
- 프롬프트 생성·응답 파싱은 `PromptBuilder` 한 곳. 파싱 실패는 `null` → 호출자가 규칙 기반 폴백.
- 호출 예산: `AgentRunScope` + `agent.pipeline.max-llm-calls`. 초과 시 `LlmBudgetExceededException`.
- 호출 이력: `agent_llm_call` (purpose/latency/chars/tokens, 마스킹됨).
- **코드로 확정할 수 있는 것**(SQL 검증·실행·분기·상한·재시도·마스킹)은 LLM 에 묻지 않는다.
- 오프라인 모드(`AGENT_LLM_MODE=offline`)는 회귀 테스트의 기준선이다. 새 규칙은 오프라인에서도 동작해야 한다.

---

## 7. 데이터·DB 규칙

- 메타데이터 단일 기준: `src/main/resources/nl2sql/schema-catalog.json` (프롬프트·가드 허용목록·PII 마스킹).
- 스키마: `schema.sql` (H2, MySQL 모드). 운영은 Flyway/Liquibase 권장 + `ALTER TABLE` 주석 유지.
- 시드: `data.sql` (MCP 6건, `INSERT ... WHERE NOT EXISTS`), `DemoDataSeeder` (앱/부서/사용자/이슈/별칭/캐시/TC).
- 컬럼 추가 시: `schema.sql` + `McpApiMaster`/`McpPayloadLog` record + repository insert/select + 문서.
- 테이블 값 변경은 재기동만으로 반영된다(코드 배포 없음).

---

## 8. 테스트·빌드

```bash
mvn clean test                                   # 전체 (현재 75개)
AGENT_LLM_MODE=offline mvn spring-boot:run       # 로컬 데모
mvn -Dtest=SqlGuardTest test                     # 단일
```

| 테스트 | 검증 |
|---|---|
| `SqlGuardTest` (10) | SELECT 전용, 허용 테이블, CTE, 묵시적 조인, INTO, 사용자 마스터, LIMIT |
| `IncidentQueryFlowServiceTest` (4) | 6단계 답변, 캐시 재사용, 멀티턴, 감사 |
| `LlmUnavailableFlowTest` (1) | LLM 연결 실패 → FAILED + 안내 |
| `AgentApiTest` (6) | API 계약, 400, 캐시 검증자, 평가 202 |
| `McpReshapingFlowTest` (16) | Raw→압축, 로우→tools/call, 3방향, HTTP/SSE, 등록/404 |
| `RestApiIntegrationTest` (12) | REST 6시드, GET/POST/PUT/DELETE/PATCH, 5xx 비은폐, 픽스처 |
| `SelfRepairEngineTest` (1) | 실행 오류 → 재생성 |
| `EvalServiceTest` (2) | 4지표/통과율/이력 |
| `ContextSmokeTest` (1) | 컨텍스트 기동 |
| `McpRegistrationSecurityTest` (5) | 시크릿 헤더/평문 auth/메타데이터/미지원 전송/로그 마스킹 |
| `SensitiveDataMaskerTest` (4) | JSON·텍스트·PII·원본 불변 |
| `OutboundTargetPolicyTest` (5) | 스킴/사용자정보/메타데이터/로컬/허용목록 |
| `SourceQueryValidatorTest` (4) | SELECT 전용/주석/변경/빈 값 |
| `ValidationUtilsTest` (4) | 공통 검증 계약 |

- 새 보안·검증 로직에는 **단위 테스트 필수**. 새 외부 연동에는 오프라인 픽스처 테스트 권장.
- 테스트는 H2 in-memory + `offline` 모드로 외부 의존 없이 돈다.

---

## 9. 변경·인수인계 절차

1. **읽기**: 이 문서 → `AGENTS.md` → 관련 클래스의 IPO 배너.
2. **계층/패키지 결정**: §3 표.
3. **공통화 확인**: 반복 검증은 `ValidationUtils`, 고정 값은 `common/constant`.
4. **구현**: 상단/중단/하단 순서, 흐름 클래스에는 구현 금지.
5. **보안 점검**: §5 체크(시크릿·마스킹·SSRF·SQL·헤더).
6. **테스트**: `mvn clean test` 전체 통과 + 새 테스트.
7. **문서**: 구조 변경 시 `AGENTS.md` 지도/표, 이 문서, `docs/architecture.md`, README §18.
8. **DB**: `schema.sql` + 운영 `ALTER TABLE` 주석 + 리포지토리 매핑.
9. **커밋**: `feat|fix|refactor|docs|test: 한국어 요약` (본문에 검증 결과).
10. **인수인계 메모**: 바꾼 규칙·새 상수·새 보안 경계·남은 한계를 커밋 본문/README 에 남긴다.

### Definition of Done
- [ ] 5대 규칙 위반 없음
- [ ] `common` 공통화 적용 (검증·상수)
- [ ] 새 클래스가 §3 표 위치
- [ ] `mvn clean test` 전체 통과
- [ ] 시크릿/PII 평문 없음
- [ ] 새 외부 호출이 `OutboundTargetPolicy` 통과
- [ ] 새 SQL 경로가 `SqlGuard`/`SourceQueryValidator` 통과
- [ ] 문서 갱신

---

## 10. 파일 인덱스 (역할별)

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `state` | `IncidentQueryFlowService` | 6단계 흐름 (유일한 질의 오케스트레이터) |
| `state.worker` | 6개 Worker | 질의이해/RAG/재사용/SQL준비/실행/답변 |
| `state.trace` | `TraceRecorder` | 단계 이력 → 로그 + `agent_run_step` |
| `mcp` | `McpOrchestratorService` | 5단계 MCP/REST 실행 흐름 |
| `mcp.transport` | `McpClientGateway`, `RestApiClientGateway`, `McpAuthHeaders` | 통신·인증 |
| `mcp.reshaping` | `DataReshaper`, `ReshapingRule` | 양방향 JSONPath 변환 |
| `mcp.repository` | `McpMasterRepository`, `McpPayloadLogRepository`, `McpSourceQueryReader` | 마스터/로그/원천 |
| `mcp.validation` | `McpApiRegistrationValidator`, `SourceQueryValidator` | 등록·원천 검증 |
| `mcp.fixture` | `McpFixtureCatalog` | 오프라인 결정적 응답 |
| `llm` | `LlmGateway`, `AgentRunScope` | LLM 계약·호출 예산 |
| `llm.gateway` | `SpringAiLlmGateway`, `HeuristicLlmGateway` | 실제/규칙 기반 구현 |
| `llm.prompt` | `PromptBuilder` | 프롬프트 생성 + 응답 파싱 |
| `nl2sql` | `SchemaCatalog` | 메타데이터 사전 |
| `nl2sql.heuristic` | `HeuristicQueryPlanner/SqlTemplates/AnswerComposer` | 규칙 기반 폴백 |
| `rag` | `VerifiedSqlCacheService` | 검증 SQL 캐시 |
| `gateway` | `SelfRepairEngine` | 검증→실행→복구 루프 |
| `gateway.guard` | `SqlGuard`, `GuardVerdict` | SQL 안전 검증 |
| `gateway.jdbc` | `DbExecutor` | JDBC 실행·정규화 |
| `gateway.mask` | `PiiMasker` | 조회 결과 PII 마스킹 |
| `security.mask` | `SensitiveDataMasker` | 로그·응답 시크릿/PII 마스킹 |
| `security.net` | `OutboundTargetPolicy` | SSRF·리다이렉트·대역 정책 |
| `common.validation` | `ValidationUtils`, `ValidationException` | 공통 검증 |
| `common.constant` | `AgentConstants`, `McpConstants`, `SecurityConstants`, `SqlConstants` | 공통 상수 |
| `eval` | `EvalService` | 품질 평가 배치 |
| `eval.metric` | `ResultSetComparator`, `HeuristicJudge` | EX/AA/FN |
| `audit.repository` | `AuditRepository`, `ConversationStore` | 감사·멀티턴 |
| `config` | `AgentProperties`, `ChatClientConfig`, `AsyncConfig` | 설정 |
| `config.seed` | `DemoDataSeeder` | 기준 데이터 |
| `web` | 4개 Controller | HTTP 계약 |
| `web.mapper` | `WebMapper` | DTO ↔ 도메인 |
| `web.handler` | `GlobalExceptionHandler` | 오류 응답 |

---

## 11. 알려진 한계 / 다음 작업 후보

- **API 인증 없음**: 사내 게이트웨이 뒤 배치 전제. Spring Security/사내 SSO 연동은 별도 과제.
- **AUTO 폴백**: 연결 실패 시 픽스처로 대체하므로 운영에서는 `transport-mode=HTTP` 권장.
- **단일 인스턴스 가정**: 평가 배치는 인메모리 스레드 풀. 다중 인스턴스면 분산 락/큐 필요.
- **RAG 임베딩**: 로컬 임베딩 모델 없으면 어휘 유사도로 폴백(품질 차이 있음).
- **SQL 파서**: 완전한 파서가 아니라 정규식·스캐너 기반. 복잡한 CTE/함수는 보수적으로 거부될 수 있음.
- **H2 파일 DB**: 운영은 MySQL + 마이그레이션 도구로 교체.

---

## 12. 다른 LLM 에게 그대로 주는 시작 프롬프트

```
이 저장소(incident-agent-spring-ai)에서 작업을 이어간다.
먼저 AGENTS.md → docs/CONVENTIONS_AND_HANDOFF.md → docs/architecture.md 순서로 읽어라.
반드시 지킬 것:
1) 흐름 클래스(Controller/Flow/Orchestrator)에는 시나리오만, SQL·HTTP·파싱 구현 금지.
2) 모든 주요 클래스는 상단(INPUT)/중단(PROCESS)/하단(OUTPUT) IPO 배너와 순서를 지킨다.
3) 업무 소스는 도메인 상위, 예외/핸들러/어댑터/검증/보조 모델은 한 단계 아래 하위 패키지.
4) 새 외부 도구는 svcm_tb_mcp_api_master 행 추가만으로. 코드에 도구별 분기 금지.
5) 반복 검증은 common/validation/ValidationUtils, 고정 값은 common/constant/* 로 공통화.
6) 시크릿/PII 평문 금지, 외부 호출은 OutboundTargetPolicy, SQL 은 SqlGuard/SourceQueryValidator 통과.
7) 변경 후 `mvn clean test` 전체 통과. 새 보안/검증 로직은 테스트 추가.
```
