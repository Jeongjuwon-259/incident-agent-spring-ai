# AGENTS.md — 이 저장소에서 일하는 모든 코딩 에이전트를 위한 지침

> 이 파일은 **Claude, GPT, Gemini, 로컬 LLM, 사람 개발자 누구든** 저장소를 열면 가장 먼저 읽는 표준 지침서다.
> 더 자세한 규칙과 예시는 [`docs/CONVENTIONS_AND_HANDOFF.md`](docs/CONVENTIONS_AND_HANDOFF.md),
> 구조 설명은 [`docs/architecture.md`](docs/architecture.md), 사용법은 [`README.md`](README.md) §18 을 본다.
> **코드를 쓰기 전에 §2(4대 규칙)와 §3(IPO)을 반드시 확인한다.**

---

## 0. 30초 요약

- **무엇**: G-SRE 서비스맵 포털의 장애 지원 AI Agent. 자연어 질문 → 6단계 상태머신 → 검증된 SQL 실행 → 근거 기반 답변.
- **스택**: Java 21 · Spring Boot 3.3.5 · Spring AI 1.0.0-M3(OpenAI 호환) · H2(개발) / MySQL(운영) · Maven.
- **핵심 구조**: `Controller → FlowService/Orchestrator → Worker/Gateway → Repository`.
- **모델 없이 전체 동작**: `AGENT_LLM_MODE=offline` (규칙 기반 결정적 구현). 테스트는 전부 이 모드.
- **빌드/테스트**:
  ```bash
  mvn clean test          # 전체 테스트 (필수 통과)
  AGENT_LLM_MODE=offline mvn spring-boot:run
  ```
- **절대 하지 말 것**: 컨트롤러/오케스트레이터에 SQL·HTTP·파싱 구현 넣기, 시크릿을 DB·로그·코드에 평문 저장,
  테이블에 도구를 추가할 때 코드에 도구별 `if/else` 추가.

---

## 1. 저장소 지도

```
com.samsung.gsre.agent
├── state/          질의 처리 6단계 흐름 (IncidentQueryFlowService) + Worker + Trace
├── mcp/            테이블 기반 MCP/REST 연동 (McpOrchestratorService)
│   ├── transport/  HTTP 게이트웨이 2종 (MCP tools/call, 순수 REST)
│   ├── reshaping/  JSONPath 룰 엔진 (DataReshaper)
│   ├── repository/ 마스터/로그/원천 SELECT
│   ├── validation/ 등록 검증 + 원천 SELECT 읽기 전용 검증
│   ├── fixture/    오프라인 결정적 응답
│   └── exception/  연동 예외
├── llm/            LlmGateway 계약 + Spring AI/규칙 기반 구현 + 프롬프트
├── nl2sql/         메타데이터 사전(SchemaCatalog) + 규칙 기반 폴백
├── rag/            검증 SQL 시맨틱 캐시
├── gateway/        SQL 실행 흐름(SelfRepairEngine) + guard/jdbc/mask 하위 패키지
├── eval/           품질 평가 하네스 (EX/AA/FN/실행)
├── audit/          감사 로그 리포지토리
├── common/         공통 검증(validation) · 고정 상수(constant)
├── security/       마스킹(mask) · 외부 호출 정책(net)
├── config/         설정 바인딩 + 시드
├── model/          공용 도메인 모델 (공유 커널)
└── web/            Controller + dto/mapper/handler
```

---

## 2. 절대 규칙 4개 (사용자가 요구한 기준)

### 규칙 1 — MVC 친화적, 플로우 로직과 구현 메소드 분리
- `Controller` / `FlowService` / `Orchestrator` 는 **"무엇을 어떤 순서로"** 만 담는다.
- 세부 가공(SQL, HTTP, JSON 파싱, 문자열 조립)은 `Worker` / `Gateway` / `Reshaper` / `Repository` / `Mapper` 로 내린다.
- 판단 기준: **흐름 클래스의 public 메서드를 위에서 아래로 읽으면 시나리오가 한눈에 보여야 한다.**
  `SELECT`, `HttpRequest`, `JSONPath`, `String.format` 이 흐름 클래스에 보이면 잘못된 것이다.

### 규칙 2 — 컨설팅형 IPO (Input → Process → Output)
모든 주요 업무 클래스는 **상단(기준정보) → 중단(가공 흐름) → 하단(결과)** 순서로 배치하고,
아래 주석 배너로 구간을 표시한다.

```java
// ==================================================================
// 상단 (INPUT) — 기준정보: 상수·임계치·설정·의존성
// ==================================================================

// ==================================================================
// 중단 (PROCESS) — 단계별 가공 흐름 (읽는 순서 = 실행 순서)
// ==================================================================

// ==================================================================
// 하단 (OUTPUT) — 결과 조립·반환·저장 형식
// ==================================================================
```
- 상수/패턴/임계치는 **반드시 클래스 최상단**. 메서드 사이에 상수를 선언하지 않는다.
- 여러 단계 메서드는 **실행 순서대로** 배치한다.
- 결과 DTO 조립은 맨 아래. 예외 변환/로그 유틸도 아래.
- 빌더+파서 쌍 클래스(`PromptBuilder`)는 "단계별 INPUT→OUTPUT 쌍"을 단계 순서대로 배치하는 예외를 허용한다(문서에 명시).

### 규칙 3 — 패키지 분리 (업무 소스 vs 패턴 소스)
- 도메인 패키지 **상위 레벨에는 업무 흐름과 핵심 도메인 모델만** 둔다.
- 예외·핸들러·저수준 어댑터·리포지토리·픽스처·검증기·마스커·보조 모델은 **한 단계 아래 하위 패키지**.
- 신규 클래스 위치는 §4 결정표를 따른다.
- 이동 리팩토링 시 **공개 API(URL·DB 스키마·JSON 규격·오류 코드)를 바꾸지 않는다.**
  필요한 클래스만 `public` 으로 열고, 테스트·문서의 import/패키지 표기를 함께 갱신한다.

### 규칙 4 — 테이블 기반 REST/MCP 확장 + 보안
- 새 외부 연동은 **`svcm_tb_mcp_api_master` 행 1개**로 끝나야 한다. 코드 수정·재배포 없음.
- `transport_type`/`http_method`/`headers_config`/`direction`/룰 JSON 이 전송 방식을 결정한다.
- 보안 규칙(§6)을 위반하는 등록·실행은 **거부**한다. 보안 예외를 만들어 통과시키지 않는다.

### 규칙 5 — 검증과 고정 상수는 `common` 패키지로 공통화
- **널/공백/필수 엔터티 존재/범위/형식 검증**을 업무 소스에 `if (x == null || x.isBlank()) throw ...` 로 흩어 놓지 않는다.
  `common/validation/ValidationUtils` 의 이름 있는 메서드를 쓴다.
  ```java
  // 나쁨 — 흐름 코드에 검증 잡음
  if (api.apiId() == null || api.apiId().isBlank()) {
      throw new IllegalArgumentException("apiId 는 필수입니다.");
  }
  // 좋음 — 의도가 이름으로 드러나고 예외 변환이 한 곳에 있다
  ValidationUtils.requireText(api.apiId(), "apiId");
  ```
- **고정 값(타임아웃 기본값·임계치·프로토콜/모드/헤더명·기본 포맷·허용 목록)** 은 `common/constant/` 에 성격별로 모은다.
  `AgentConstants`(전역 기본값·ID 형식), `McpConstants`(MCP/REST 문자열), `SecurityConstants`(마스킹·SSRF),
  `SqlConstants`(SQL 금지 키워드·정책값).
- 도메인에만 의미 있는 상수는 그 도메인의 `common/constant` 하위에 두고, **같은 문자열을 두 곳에 적지 않는다.**
- 상수를 추가할 때는 `application.yml` 기본값·README §10 설정표도 함께 맞춘다.

---

## 3. 새 코드 작성 순서 (권장)

1. **어느 계층인가** 결정: 흐름 / 단계 처리 / 어댑터 / 리포지토리 / 검증·패턴 / 표현(web).
2. **패키지 결정** (§4 표).
3. **검증·상수 위치**를 먼저 확인: 반복 검증은 `common/validation/ValidationUtils`, 고정 값은 `common/constant/*`.
4. **클래스 골격**을 IPO 배너로 시작:
   ```java
   @Component
   @RequiredArgsConstructor
   public class XxxWorker {
       // 상단: 의존성 + 임계치
       private final ...
       private static final int MAX_... = ...;

       // 중단: public 진입 메서드 1개 + 단계별 private 메서드
       public StageOutcome run(PipelineContext ctx) { ... }

       // 하단: 결과 조립/유틸
       private StageOutcome outcome(...) { ... }
   }
   ```
5. **판단은 LLM, 확정은 코드**: SQL 검증·실행·분기·상한·재시도는 LLM 에 맡기지 않는다.
6. **테스트 추가** 후 `mvn clean test`.
7. **문서 갱신**: 구조가 바뀌면 이 파일의 지도/표, `docs/architecture.md`, README §18.

---

## 4. 패키지 배치 결정표

| 새 클래스의 성격 | 위치 | 예 |
|---|---|---|
| 공통 검증(널/공백/존재/범위) | `common/validation/` | `ValidationUtils`, `ValidationException` |
| 공통 고정 상수 | `common/constant/` | `AgentConstants`, `McpConstants`, `SecurityConstants`, `SqlConstants` |
| 업무 흐름(시나리오) | `<도메인>/` | `IncidentQueryFlowService`, `McpOrchestratorService`, `EvalService` |
| 핵심 도메인·상태 모델 | `<도메인>/`, `model/` | `PipelineContext`, `McpApiMaster`, `QueryPlan` |
| 단계별 처리 | `state/worker/` | `AnswerGenerationWorker` |
| 단계 이력 | `state/trace/` | `TraceRecorder`, `TraceStep` |
| 예외 | `<도메인>/exception/` | `mcp/exception/*`, `llm/exception/LlmException` |
| 전역 오류 응답 | `web/handler/` | `GlobalExceptionHandler` |
| HTTP 어댑터·인증 | `mcp/transport/` | `McpClientGateway`, `RestApiClientGateway`, `McpAuthHeaders` |
| 변환·룰 엔진 | `mcp/reshaping/`, `llm/prompt/`, `web/mapper/` | `DataReshaper`, `PromptBuilder`, `WebMapper` |
| 리포지토리·원천 조회 | `<도메인>/repository/` | `McpPayloadLogRepository`, `McpSourceQueryReader` |
| 등록/입력 검증(패턴) | `<도메인>/validation/` | `McpApiRegistrationValidator`, `SourceQueryValidator` |
| SQL 안전 검증 | `gateway/guard/` | `SqlGuard`, `GuardVerdict` |
| JDBC 실행 어댑터 | `gateway/jdbc/` | `DbExecutor` |
| PII 마스킹 규칙 | `gateway/mask/` | `PiiMasker` |
| 시크릿/PII 마스킹 | `security/mask/` | `SensitiveDataMasker` |
| 외부 호출 네트워크 정책 | `security/net/` | `OutboundTargetPolicy` |
| 픽스처·시드 | `mcp/fixture/`, `config/seed/` | `McpFixtureCatalog`, `DemoDataSeeder` |
| 규칙 기반 폴백 | `nl2sql/heuristic/` | `HeuristicSqlTemplates` |
| 보조 모델·구현 | `nl2sql/metadata/`, `rag/similarity/`, `eval/metric/`, `llm/support/`, `llm/gateway/` | `TableMetadata`, `SemanticSimilarity`, `JsonSupport` |

**금지**: `gateway/` 상위에 검증기·유틸을 다시 쌓기, `mcp/` 상위에 새 어댑터 추가, `web/` 에 도메인 로직 두기.

---

## 5. 테이블 기반 도구 추가 절차 (코드 수정 금지)

1. `svcm_tb_mcp_api_master` 에 행 추가 (또는 `POST /api/v1/mcp/apis`).
2. 필수 값:
   - `direction`: `OUTBOUND` | `INBOUND` | `BIDIRECTIONAL`
   - `transport_type`: `MCP|HTTP|SSE` (tools/call) 또는 `REST|REST_API` (순수 REST)
   - `http_method`: `GET|POST|PUT|PATCH|DELETE` (REST만 의미)
   - `endpoint_url`: `http(s)://...`, 경로변수 `{name}` 허용
   - `auth_type` + `auth_config`: **시크릿이 아니라 환경변수 이름** (`{"envVar":"MCP_TOKEN"}`)
   - `headers_config`: 비밀이 아닌 헤더만. 인증 헤더 금지.
   - `request_reshaping_rule` / `response_reshaping_rule`: JSONPath 룰
   - INBOUND/BIDIRECTIONAL 이면 `source_query` (읽기 전용 단일 SELECT)
3. 실행: `POST /api/v1/mcp/execute/{apiId}` → `request`/`response`/`stages`/감사 `logId` 확인.
4. 확인: `GET /api/v1/mcp/logs?apiId=...` 에서 raw ↔ reshaped 비교. **시크릿/PII 는 마스킹되어 있다.**

예시:
```bash
curl -s -X POST localhost:8080/api/v1/mcp/apis -H 'Content-Type: application/json' -d '{
  "apiId":"REST-USER-001","apiName":"사용자 조회","toolName":"user.get","direction":"OUTBOUND",
  "transportType":"REST","httpMethod":"GET","endpointUrl":"https://api.example.com/users/{userId}",
  "authType":"BEARER","authConfig":"{\"envVar\":\"USER_API_TOKEN\"}",
  "responseReshapingRule":"{\"mode\":\"OBJECT\",\"fields\":[{\"target\":\"name\",\"source\":\"$.name\"}]}"
}'
```

**하지 말 것**: 새 도구 때문에 `McpOrchestratorService` 에 `if (apiId.equals(...))` 추가, 게이트웨이에 도구별 분기 추가.
정말 새 프로토콜이면 `McpCallResult` 를 돌려주는 게이트웨이 1개 + Orchestrator 의 분기 1줄만 추가한다.

---

## 6. 보안 규칙 (위반 시 머지 금지)

### 6.1 시크릿
- DB(`auth_config`, `headers_config`), 코드, 로그에 시크릿 평문 금지. `auth_config` 는 **환경변수 이름만**.
- 등록 검증이 `auth_config` 의 `value/secret/token/...` 키와 `headers_config` 의 인증 헤더를 거부한다.
- 환경변수가 없으면 헤더만 생략한다(로컬 데모). 값을 로그로 찍지 않는다.

### 6.2 로그·감사 테이블
- `svcm_tb_mcp_payload_log`, `agent_llm_call`, `agent_run_step`, `agent_run` 에 쓰기 전에
  `SensitiveDataMasker` 로 마스킹한다.
- 마스킹 대상: `Bearer/Basic` 토큰, `*token*/*secret*/*password*/*apiKey*` 키, 이메일, 전화번호, 주민등록번호.
- **자르기 전에 마스킹**한다. (경계에서 잘린 값 보호)
- API 로 payload 를 돌려줄 때도 `SensitiveDataMasker.mask(...)` 를 거친다.

### 6.3 외부 호출 (SSRF)
- 모든 외부 호출은 `OutboundTargetPolicy.verify(url)` 를 **등록 시점 + 전송 직전** 2회 통과한다.
- `http/https` 만, URL 사용자정보 금지, 리다이렉트 금지(`Redirect.NEVER`).
- 메타데이터/링크로컬/멀티캐스트/미지정 주소는 항상 차단.
- 운영: `agent.mcp.network.allow-local-endpoints=false` + `allowed-hosts` 명시.

### 6.4 SQL
- `SqlGuard`: SELECT/WITH 단일 문장, 주석·다중문장 금지, 허용 테이블만(CTE 제외), `FROM a, b` 금지,
  DML/DDL/`INTO`/`CALL`/`RUNSCRIPT` 등 금지, LIMIT 강제, 사용자 마스터 `SELECT *` 금지.
- `SourceQueryValidator`: 읽기 전용 단일 SELECT, 주석·세미콜론·변경 키워드 금지, 행 수 상한.
- 파라미터는 **항상 named parameter**. 문자열로 SQL 을 조립하지 않는다.

### 6.5 헤더·전송
- 헤더 이름/값 형식 검증, 개행(CR/LF) 금지, 인증 헤더는 `auth_config` 로만.
- 타임아웃(`api.timeoutMs`, connect-timeout) 필수. 실패는 숨기지 않는다(4xx/5xx → FAILED).
- AUTO 모드의 픽스처 폴백은 **연결 실패에만**. HTTP 오류를 성공으로 덮지 않는다.

---

## 7. LLM 작업 규칙

- LLM 호출은 `LlmGateway` 뒤에서만. 새 호출 지점을 만들면 예산(`max-llm-calls`)·기록(`agent_llm_call`)을 함께 고려한다.
- 프롬프트는 `PromptBuilder`, 파싱도 `PromptBuilder`. 파싱 실패는 `null` → 호출자가 규칙 기반 폴백.
- 프롬프트에 넣는 데이터는 최소화하고, 결과 행 수를 제한한다.
- **코드로 확정 가능한 것**(검증·분기·상한·재시도·마스킹)을 LLM 에 묻지 않는다.

---

## 8. 테스트·빌드

```bash
mvn clean test                                   # 전체 (필수, 현재 75개)
AGENT_LLM_MODE=offline mvn spring-boot:run       # 로컬 데모
mvn -Dtest=SqlGuardTest test                     # 단일 테스트
```
- 새 보안/검증 로직에는 **단위 테스트를 반드시** 추가한다.
- 테스트는 `offline` 모드 + H2 in-memory 로 외부 의존 없이 돈다.
- 테스트가 실제 로컬 서버를 띄우는 경우 `127.0.0.1` 을 쓴다(정책 기본값이 로컬 허용).

---

## 9. 변경·인수인계 체크리스트

- [ ] 4대 규칙(§2) 위반 없음
- [ ] 새 클래스가 §4 표의 위치에 있음 (공통 검증/상수는 `common/`)
- [ ] 반복 검증 `if` 를 `ValidationUtils` 로, 고정 값을 `common/constant` 로 뺐음
- [ ] IPO 배너/순서 준수 (상수는 최상단)
- [ ] 시크릿·PII 가 DB/로그/응답에 평문으로 없음
- [ ] 새 외부 호출이 `OutboundTargetPolicy` 를 통과함
- [ ] 새 SQL 경로가 `SqlGuard`/`SourceQueryValidator` 를 통과함
- [ ] `mvn clean test` 전체 통과
- [ ] README/docs/이 파일의 구조·규칙·테스트 수 갱신
- [ ] DB 변경 시 `schema.sql` + 운영용 `ALTER TABLE` 주석 추가
- [ ] 커밋 메시지: `feat|fix|refactor|docs|test: 한국어 요약`

---
_최종 갱신: 2026-11-14 · 전체 소스 재검수(4대 규칙 + 공통화 규칙) + 보안 보완 + LLM 인수인계 문서화_
