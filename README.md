# incident-agent-spring-ai

G-SRE 서비스맵 포털 **장애 지원 AI Agent** — Java 21 / Spring Boot 3.3.5 / Spring AI 1.0.0-M3 (OpenAI-compatible)

자연어 질의를 받아 **6단계 명시적 상태머신**으로 조회 계획 → 검증 SQL 시맨틱 캐시(RAG) → 재사용 판단 → NL2SQL → 검증·실행·Self-Repair → 근거 기반 답변까지 처리하고, **EX/AA/FN/실행 성공** 4개 지표의 품질 평가 하네스로 회귀 검증하는 백엔드입니다.

- Flowise/LangGraph 없이 **Java 코드로 흐름을 명시적으로 제어**합니다.
- Mac mini 로컬 LLM(Ollama, vLLM, llama.cpp, LM Studio, Qwen, Muse …)을 **OpenAI 호환 엔드포인트**로 붙입니다. 주소/모델은 환경변수 한 줄로 교체됩니다.
- 모델이 없어도 `AGENT_LLM_MODE=offline` 으로 **전체 파이프라인·감사로그·품질평가가 끝까지 동작**합니다 (규칙 기반 결정적 구현). 테스트 50개가 이 모드로 실행됩니다.
- **REST API + MCP 통합 확장**: 같은 테이블(`svcm_tb_mcp_api_master`)의 `transport_type`/`http_method` 값만으로 MCP(tools/call)와 순수 REST(GET/POST/PUT/PATCH/DELETE)를 함께 구동합니다. ([§16](#16-rest-api--mcp-테이블-기반-통합-확장-2026-11-14-추가))
- H2 임베디드 DB + 시드 데이터(8대 테이블, 검증 SQL 6건, 골든셋 6건)로 받자마자 확인 가능합니다.
- **MCP API 확장**: 테이블 등록만으로 외부 도구를 늘리고, 선언적 JSONPath 룰로 요청/응답을 양방향 Reshaping 합니다. ([§15](#15-mcp-api-확장과-양방향-reshaping-2026-11-13-추가))

---

## 1. 빠른 시작

### 요구사항
- JDK 21
- Maven 3.9+
- (선택) 로컬 LLM 서버 — 없으면 오프라인 모드로 실행

### 실행

```bash
# 1) 모델 없이 전체 흐름 확인 (가장 빠른 길)
AGENT_LLM_MODE=offline mvn spring-boot:run

# 2) Mac mini 로컬 LLM 연결 (Ollama 기본값)
LOCAL_LLM_BASE_URL=http://localhost:11434/v1 \
LOCAL_LLM_MODEL=qwen2.5:14b \
mvn spring-boot:run
```

- 웹 콘솔: <http://localhost:8080/> — 질의 테스트 / 품질 평가 / 검증 SQL 캐시
- H2 콘솔: <http://localhost:8080/h2-console> — JDBC URL `jdbc:h2:file:./agent-db`, user `sa`, password 없음
- 첫 기동 시 `agent-db.mv.db` 파일이 생성되고 데모 데이터가 1회 삽입됩니다. 초기화하려면 파일을 지우고 다시 실행하세요.
- 운영/테스트에서 시드가 필요 없으면 `AGENT_DEMO_SEED=false`.

### 30초 확인

```bash
curl -s -X POST http://localhost:8080/api/v1/agent/query \
  -H 'Content-Type: application/json' \
  -d '{"userId":"jung","channel":"WEB","sessionId":"s1","question":"APP-1001 서비스맵포털 담당자 알려줘"}'
```

```json
{
  "runId": "RUN-20261113203012-3F2A",
  "status": "SUCCESS",
  "answer": "APP-1001 서비스맵포털의 담당자는 김민수(SRE플랫폼팀)입니다. 이메일 ki***@samsung.com, 연락처 010-****-5678.",
  "cacheHit": false,
  "planIntent": "CONTACT",
  "llmCalls": 0,
  "durationMs": 38,
  "lookups": [{ "purpose": "APP-1001 담당자 조회", "origin": "GENERATED", "rowCount": 1, "sql": "..." }],
  "trace": [
    { "seq": 1, "stage": "QUERY_UNDERSTANDING", "summary": "의도=CONTACT, 조회작업 1개 계획" },
    { "seq": 2, "stage": "RAG_CACHE_SEARCH", "summary": "후보 1건 (task=T1)" },
    { "seq": 3, "stage": "REUSE_DECISION", "summary": "신규 생성 필요" },
    { "seq": 4, "stage": "SQL_PREPARATION", "summary": "신규 SQL 생성" },
    { "seq": 5, "stage": "SQL_VALIDATE_EXECUTE", "summary": "실행 성공 1건 (GENERATED)" },
    { "seq": 6, "stage": "ANSWER_GENERATION", "summary": "답변 생성 (sufficient=true, 조회 1건)" }
  ]
}
```

같은 질의를 다시 보내면 `cacheHit=true`, `llmCalls` 가 줄고 `REUSE_DECISION` 단계가 "검증 SQL 재사용" 으로 바뀝니다.

---

## 2. 로컬 LLM 연결

`src/main/resources/application.yml` 의 `spring.ai.openai` 블록이 유일한 연결 지점입니다.

```yaml
spring:
  ai:
    openai:
      base-url: ${LOCAL_LLM_BASE_URL:http://localhost:11434/v1}
      api-key: ${LOCAL_LLM_API_KEY:local-no-key}
      chat:
        options:
          model: ${LOCAL_LLM_MODEL:qwen2.5:14b}
      embedding:
        options:
          model: ${LOCAL_LLM_EMBEDDING_MODEL:nomic-embed-text}
```

| 로컬 LLM | `LOCAL_LLM_BASE_URL` | 모델 예시 | 비고 |
|---|---|---|---|
| Ollama | `http://localhost:11434/v1` | `qwen2.5:14b`, `qwen2.5-coder:14b` | `ollama pull qwen2.5:14b`, 임베딩 `ollama pull nomic-embed-text` |
| vLLM | `http://localhost:8000/v1` | `Qwen/Qwen2.5-14B-Instruct` | `--served-model-name` 값과 모델명 일치 |
| llama.cpp (`llama-server`) | `http://localhost:8081/v1` | 서버에 올린 GGUF | 앱이 8080 을 쓰므로 포트 분리 |
| LM Studio | `http://localhost:1234/v1` | 로드한 모델명 | Developer → Local Server 활성화 |
| Muse / 기타 OpenAI 호환 | 게이트웨이 주소 | 게이트웨이 모델명 | api-key 필요 시 `LOCAL_LLM_API_KEY` |

**권장 조건**
- 컨텍스트 8K 이상 (메타데이터 사전 + 유사 SQL + 결과를 프롬프트에 넣습니다)
- temperature 0 (코드에서 0.0 고정)
- JSON 출력이 안정적인 모델일수록 좋습니다. 파싱 실패 시 규칙 기반 폴백이 동작하지만, 그만큼 LLM 판단 품질은 떨어집니다.

**임베딩이 없으면** 시맨틱 캐시가 자동으로 어휘 유사도(한국어 글자 bigram 코사인)로 폴백합니다.
임베딩을 끄려면 `AGENT_RAG_EMBEDDING_ENABLED=false`.

**연결 실패 시** 답변 대신 다음 안내가 반환됩니다 (원인 추적용):
> 로컬 LLM 에 연결하지 못해 조회를 시작하지 못했습니다. LOCAL_LLM_BASE_URL(현재 …) 을 확인해 주세요. 모델 없이 확인하려면 AGENT_LLM_MODE=offline …

---

## 3. 아키텍처

### 3.1 6단계 명시적 상태머신

```
사용자 질의
   │
   ▼
[1] 질의 이해 ──────────────── LLM 1회: 의도·대상 APP·조회 작업 계획 (+멀티턴 맥락)
   │
   ▼
[2] RAG 캐시 검색 ─────────── 검증 SQL 후보 Top-K (임베딩 / 어휘 유사도)
   │
   ▼
[3] 재사용 판단 ───────────── LLM 1회: 대상·조건·파라미터 적합성 (유사도만으로 확정 안 함)
   │
   ▼
[4] SQL 준비 ──────────────── 재사용 적용 / NL2SQL 생성 (메타데이터 + 유사 SQL 참고)
   │
   ▼
[5] 검증·실행·Self-Repair ─── SqlGuard → DbExecutor → 오류 피드백 재생성 (최대 N회)
   │
   ├── 계획된 조회가 남아 있으면 → [2] 로 (LLM 호출 없이 다음 작업)
   ▼
[6] 결과 확인 + 답변 생성 ──── LLM 1회: 충분성 판단 + 최종 답변 (통합 호출)
   │
   └── 부족하면 → [2] 로 추가 조회 (최대 라운드)
```

- 2~5단계는 **조회 작업(LookupTask)마다** 반복됩니다.
- 6단계는 **충분성 판단과 답변 생성을 한 번의 LLM 호출**로 처리합니다 (요구사항 §3.4).
- 계획된 작업이 남아 있으면 답변을 만들지 않고 다음 작업으로 넘어갑니다 (불필요한 LLM 호출 제거).
- 모든 반복은 상한이 있습니다: `max-tasks(5)`, `max-executions(6)`, `max-extra-rounds(2)`, `max-llm-calls(24)`.

### 3.2 왜 LangGraph 가 아니라 Java 상태머신인가

- 분기·반복·종료 조건이 **코드에 그대로 보입니다** (`IncidentQueryFlowService.handle`).
- 각 단계의 입력/출력/판단/오류가 `PipelineContext` 한 객체에 모여 **그대로 감사 로그**가 됩니다.
- LLM 판단이 필요한 지점(1·3·4·5-repair·6)과 일반 코드로 확정할 지점(검증·실행·분기·상한)이 패키지 수준에서 분리됩니다.
- Spring MVC 개발자에게 익숙한 `Controller → Flow Service → Worker/Gateway` 구조라 분석 비용이 낮습니다.

### 3.3 클래스 배치 원칙 (기준정보 → 가공 흐름 → 결과)

모든 핵심 클래스는 같은 순서로 읽힙니다.

| 구간 | 내용 | 예 |
|---|---|---|
| 상단 | 비즈니스 룰·메타데이터·임계치·재시도 횟수 | `SqlGuard` 의 허용 구문/금지 키워드/최대 행, `HeuristicJudge` 의 판정 임계치, `VerifiedSqlCacheService` 의 유사도 기준 |
| 중단 | 기준정보를 써서 데이터를 정제·전이하는 흐름 | `IncidentQueryFlowService.handle()` 의 6단계, 각 Worker 의 `handle()` |
| 하단 | 최종 산출물·DTO·결과 스펙 | `PipelineResult`, `LookupResult`, `EvalCaseResult`, `GuardVerdict` |

### 3.4 패키지 구조

```
com.samsung.gsre.agent
├── config        ChatClientConfig(로컬 LLM), AgentProperties(정책), AsyncConfig, DemoDataSeeder
├── state         IncidentQueryFlowService(6단계 흐름), PipelineContext, Stage, TraceRecorder
│   └── worker    단계별 Worker 6개 (질의이해/RAG/재사용판단/SQL준비/실행/답변)
├── llm           LlmGateway, SpringAiLlmGateway, HeuristicLlmGateway, PromptBuilder, AgentRunScope
├── rag           VerifiedSqlCacheService, SemanticSimilarity, LexicalSimilarity, Repository
├── nl2sql        SchemaCatalog(8대 메타데이터), HeuristicQueryPlanner/SqlTemplates/AnswerComposer
├── gateway       SqlGuard(안전 검증), DbExecutor(JDBC), SelfRepairEngine(재시도 루프)
├── eval          EvalService(배치), ResultSetComparator(EX), HeuristicJudge(AA/FN), Repository
├── mcp           McpOrchestratorService(IPO 5단계), DataReshaper(JSONPath 룰 엔진),
│                 McpClientGateway(tools/call), McpSourceQueryReader(INBOUND 원천), Repository
├── audit         AuditRepository(실행/단계/LLM 이력), ConversationStore(멀티턴)
└── web           AgentController, EvalController, CacheController, McpApiController, WebMapper, DTO
```

---

## 4. 요구사항 매핑

| 요구사항 | 구현 |
|---|---|
| §1 핵심목표: 응답속도 최적화 | TEMPLATE/캐시 재사용, 단계별 LLM 호출 최소화(계획 1 + 작업당 판단/생성 + 답변 1), 충분성·답변 통합 호출, `max-llm-calls` 상한 |
| §1 RAG 기반 유사 질의 검증 SQL | `VerifiedSqlCacheService` + `SemanticSimilarity` (임베딩/어휘 폴백) |
| §1 멀티턴 세션관리 | `ConversationStore` + `PipelineContext.history` → 질의 이해 프롬프트에 반영, APP ID 승계 |
| §1 단계별 디버깅·오류 추적 | `TraceRecorder` → `agent_run_step`, `agent_llm_call`, `GET /api/v1/agent/runs/{runId}` |
| §1 검증 SQL 축적 | `verified_sql_cache` + `POST /api/v1/cache` (검증자 필수) |
| §2 코드 기반 구조 / 명시적 제어 | LangGraph 없이 `Stage` + Worker + Flow Service, 상한·종료조건 코드 고정 |
| §2 LLM 영역과 코드 영역 구분 | LLM: 1·3·4·5-repair·6 / 코드: SqlGuard·DbExecutor·분기·재시도·상한 |
| §3.1 6단계 처리 | 위 3.1 다이어그램 그대로 |
| §3.2 RAG 두 목적 (재사용/참고) | 재사용 판단 통과 시 적용, 실패 시 후보 SQL 을 NL2SQL 참고자료로 전달 |
| §3.2 유사도만으로 재사용 확정 금지 | `ReuseDecisionWorker` + LLM 판단 + 파라미터 병합 |
| §3.3 Self-Repair | `SelfRepairEngine` (검증/실행 오류 피드백 → 재생성 → 재검증 → 재실행, 기본 2회) |
| §3.3 Multi-step / 무한 반복 방지 | `pendingTasks` 큐 + `max-executions`/`max-extra-rounds` + 동일 목적 결과 재사용 |
| §3.3 단일 SQL 우선 | 질의 이해 프롬프트 규칙 + 계획 단계에서 1개 우선 |
| §4 8대 테이블 메타데이터 | `nl2sql/schema-catalog.json` → `SchemaCatalog` (프롬프트·가드·PII 에 동시 사용) |
| §5 감사 로그 | `agent_run`, `agent_run_step`, `agent_llm_call`, `agent_conversation_turn`, 동일 runId 연결 |
| §5 민감정보 정책 | PII 컬럼 마스킹(`agent.guard.mask-pii`), 사용자 마스터 정책(MASK/DENY/ALLOW), 실행 결과 접근은 API 인가에 위임 |
| §6 품질 평가 하네스 | `EvalService` + `ResultSetComparator`(EX) + `HeuristicJudge`/LLM-as-a-Judge(AA·FN) + 실행 성공 |
| §6.1 4지표·종합판정 | 모두 Pass 일 때만 종합 Pass, 지표별 사유 저장 |
| §6.2 데이터 구조 | `eval_test_case`, `eval_batch`, `eval_case_result` (스냅샷·이력 누적) |
| §6.3 웹 실행/조회 | `/api/v1/eval/*` + 콘솔 UI (선택·전체 실행, 배치 통과율, TC별 결과/이력) |
| §6.4 자동 평가 방식 | EX·실행 성공 = 프로그램 기반, AA·FN = LLM-as-a-Judge, 종합 = 프로그램 기반 |
| §6.5 통과율 | 배치별 `pass_rate`, 지표별 Pass 수 집계, TC 이력으로 추세 비교 |
| §6.6 지속 개선 | 실패 TC·사유 조회, TC 추가/수정, 동일 TC 재평가 이력 |

---

## 5. API

### 질의

```bash
POST /api/v1/agent/query
{
  "userId": "jungjuwon", "channel": "WEB", "sessionId": "s-001",
  "question": "APP-1003 배포관리콘솔 관련 Jira 작업이력 보여줘"
}
```

응답에는 `runId`, `status`(SUCCESS/PARTIAL/FAILED), `answer`, `cacheHit`, `lookups`(SQL·파라미터·행 수·미리보기·Self-Repair 이력), `trace`(6단계 이력)가 포함됩니다.

```bash
GET  /api/v1/agent/runs/{runId}        # 실행 + 단계 + LLM 호출 이력
GET  /api/v1/agent/runs?sessionId=s-001&limit=20
```

### 검증 SQL 캐시

```bash
GET  /api/v1/cache
POST /api/v1/cache
{
  "question": "APP-1001 서비스맵포털 담당자 알려줘",
  "sql": "SELECT a.app_id, a.app_name, u.user_name FROM ... WHERE a.app_id = :appId LIMIT 20",
  "parameters": {"appId": "APP-1001"},
  "verifiedBy": "jungjuwon",
  "notes": "2026-11-13 수동 검증"
}
```

- `verifiedBy` 없으면 400. **파이프라인은 캐시에 자동 등록하지 않습니다** (요구사항: 검증 없이 자동 등록 금지).
- 등록 SQL 도 `SqlGuard` 를 통과해야 합니다 (SELECT 전용, 허용 테이블, LIMIT).

### 품질 평가

```bash
GET  /api/v1/eval/cases                       # 목록
POST /api/v1/eval/cases/TC-007                # 등록 (tcId 지정)
PUT  /api/v1/eval/cases/TC-007                # 수정
POST /api/v1/eval/batches                     # 실행 접수 (202)
     {"tcIds":["TC-001","TC-002"],"all":false,"requestedBy":"jungjuwon"}
     {"all":true}
GET  /api/v1/eval/batches?limit=20
GET  /api/v1/eval/batches/{batchId}           # 상태·통과율 (RUNNING/COMPLETED/FAILED)
GET  /api/v1/eval/batches/{batchId}/results   # TC별 EX/AA/FN/EXEC/종합 + 사유
GET  /api/v1/eval/cases/{tcId}/history        # 동일 TC 과거 평가 이력
```

---

## 6. 품질 평가 하네스

### 지표

| 지표 | 방식 | 판정 규칙 |
|---|---|---|
| **EX** Execution Accuracy | 프로그램 | 예상 SQL 과 실제 SQL 을 각각 실행해 결과셋 비교. 행 순서 무시, 컬럼명 다르면 값 기준, 숫자는 BigDecimal 비교. 문장이 달라도 결과가 같으면 Pass |
| **AA** Answer Accuracy | LLM-as-a-Judge | 예상 답변과 실제 답변의 핵심 정보 일치 (표현 차이 허용) |
| **FN** Faithfulness | LLM-as-a-Judge | 실제 조회 결과에 없는 값·추론이 답변에 있으면 Fail |
| **실행 성공** | 프로그램 | SQL 실행 오류 여부 |

종합은 네 지표가 모두 Pass 일 때만 Pass. 각 지표의 사유를 저장합니다.

### 배치 흐름

1. 웹/API 에서 TC 선택 → Batch ID 생성 (`EVAL-yyyyMMddHHmmss-XXX`), 상태 `RUNNING`
2. 비동기 워커가 TC 마다 실제 Agent(`IncidentQueryFlowService`) 호출
3. SQL·결과·최종 답변 수집 → EX/AA/FN/실행 판정
4. 결과 저장 + 진행률/통과율 갱신 → `COMPLETED`/`FAILED`
5. 조회: 배치 통과율, TC별 지표·사유, TC 이력(품질 추이)

### 시드 골든셋

`TC-001` ~ `TC-006` (담당자, 사용자 수, 부서장, Jira 이력, 별칭, 서비스맵) — 오프라인 모드에서 **6/6 Pass** 로 검증됩니다 (`EvalServiceTest`).

---

## 7. 감사 로그와 디버깅

| 테이블 | 내용 |
|---|---|
| `agent_run` | runId, 사용자, 채널, 세션, 원본 질의, 상태, 답변, 캐시 재사용, 실행 수, LLM 호출 수, 반복 라운드, 소요시간 |
| `agent_run_step` | 단계(1~6), 요약, 상세(계획/후보/판단/SQL/오류), 지연 |
| `agent_llm_call` | 단계별 프롬프트·응답(8K 절단), 지연, 글자 수, 추정 토큰, 성공/오류 |
| `agent_conversation_turn` | 세션별 user/assistant 턴 (멀티턴 맥락) |
| `svcm_tb_mcp_payload_log` | MCP 호출 1건의 raw in / reshaped in / raw out / reshaped out, 상태코드, 소요시간, 오류 |

같은 `runId` 로 묶여 있어 "어느 단계에서 어떤 SQL 이 왜 실패했는지" 를 한 번에 추적할 수 있습니다.
개발 로그도 같은 runId 를 찍습니다: `[stage] runId=... #5/5 SQL_VALIDATE_EXECUTE | 실행 실패: ...`.

---

## 8. 보안·가드레일

- **SELECT/WITH 단일 문장만** 실행. 다중 문장·주석·DML/DDL 은 실행 전 차단.
- **허용 테이블 목록** = 메타데이터 사전의 8대 테이블/뷰. 목록 밖 테이블은 재시도 없이 거부.
- **LIMIT 강제**: 없으면 부여, `max-rows(200)` 초과 시 보정.
- **사용자 마스터 정책**: `MASK`(기본, 직접 조회 허용 + PII 마스킹), `DENY`(직접 조회 차단), `ALLOW`.
- **PII 마스킹**: 이메일 `ki***@domain`, 연락처 `010-****-5678`, 사번 `S1****`.
- **검증 SQL 등록**은 검증자 필수 + 가드 통과.
- 모든 SQL 은 단일 실행기(`DbExecutor`)를 통과하며 쿼리 타임아웃(5초)이 걸립니다.

---

## 9. 데이터·메타데이터

- `src/main/resources/nl2sql/schema-catalog.json` — 8대 테이블/뷰의 컬럼·타입·설명·JOIN·비즈니스 규칙. 이 파일 하나가 프롬프트, 가드 허용목록, PII 마스킹의 공통 기준입니다.
- `schema.sql` — H2 스키마 (업무 8테이블 + Jira 원장/뷰 + 감사 + 캐시 + 평가 + MCP 마스터/로그)
- `data.sql` — MCP API 데모 시드 3건 (재기동해도 중복 삽입되지 않는 `INSERT ... WHERE NOT EXISTS`)
- `DemoDataSeeder` — 앱 6, 부서 4, 사용자 8, 권한 17, 이슈 6, 별칭 5, 검증 SQL 6, 골든셋 6
- 운영 DB 는 MySQL 로 교체하고 `schema.sql` 대신 마이그레이션 도구(Flyway/Liquibase)를 권장합니다.

---

## 10. 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `LOCAL_LLM_BASE_URL` | `http://localhost:11434/v1` | OpenAI 호환 엔드포인트 |
| `LOCAL_LLM_MODEL` | `qwen2.5:14b` | 채팅 모델 |
| `LOCAL_LLM_API_KEY` | `local-no-key` | 필요 시 |
| `LOCAL_LLM_EMBEDDING_MODEL` | `nomic-embed-text` | 임베딩 모델 |
| `AGENT_LLM_MODE` | `local` | `local` / `offline` |
| `AGENT_RAG_EMBEDDING_ENABLED` | `true` | 임베딩 사용 여부 (실패 시 자동 폴백) |
| `AGENT_RAG_SIMILARITY` | `auto` | `auto` / `embedding` / `lexical` |
| `AGENT_MAX_REPAIR_ATTEMPTS` | `2` | Self-Repair 횟수 |
| `AGENT_GUARD_USER_MASTER_MODE` | `MASK` | `MASK` / `DENY` / `ALLOW` |
| `AGENT_DEMO_SEED` | `true` | 데모 데이터 삽입 |
| `AGENT_MCP_TRANSPORT_MODE` | `AUTO` | `AUTO` / `OFFLINE` / `HTTP` (MCP 통신 모드) |
| `AGENT_MCP_MAX_SOURCE_ROWS` | `100` | INBOUND 원천 SELECT 결과 상한 |
| `SERVER_PORT` | `8080` | |

세부 상한(tasks/executions/llm-calls/threshold/max-rows)은 `application.yml` 의 `agent.*` 에 있습니다.

---

## 11. 테스트

```bash
mvn test
```

| 테스트 | 검증 내용 |
|---|---|
| `SqlGuardTest` (7) | SELECT 전용, 허용 테이블, 사용자 마스터 `SELECT *`, 다중문장/주석, LIMIT 보정 |
| `IncidentQueryFlowServiceTest` (4) | 6단계 답변, 캐시 재사용, 멀티턴 APP 승계, 감사 이력 저장 |
| `SelfRepairEngineTest` (1) | 실행 오류 → 재생성 → 재실행 성공과 repair 이력 |
| `EvalServiceTest` (2) | 배치 4지표 Pass/통과율, TC 이력 누적 |
| `AgentApiTest` (6) | 질의 API, 검증 오류 400, 검증자 없는 캐시 등록 거부, 평가 접수 202 |
| `LlmUnavailableFlowTest` (1) | 로컬 LLM 연결 실패 시 FAILED + 조치 안내 (SUCCESS 로 덮이지 않음) |
| `ContextSmokeTest` (1) | 컨텍스트 기동 + 파이프라인 답변 |
| `McpReshapingFlowTest` (16) | K8s Raw→Pod 압축, 로우→tools/call 변환, 필수필드/문법 오류, 3방향 실행·감사로그, 실제 HTTP/SSE 통신, 등록/실행/404/검증 |
| `RestApiIntegrationTest` (12) | REST 시드 6건 조회, GET 경로변수·Query String·헤더 실통신, POST 순수 REST JSON, BIDIRECTIONAL webhook, PUT/DELETE/PATCH 메서드별 전송 형태, HTTP 500 비은폐, 오프라인 픽스처 결정성, 등록 검증 400 3종 |

현재 **50개 테스트 전부 통과** (오프라인 모드, H2 in-memory).

---

## 12. 운영 전환 체크리스트

| 로컬 데모 | 운영 |
|---|---|
| H2 file DB | MySQL (SqlScopeGuard/감사 테이블 포함), Flyway 마이그레이션 |
| 어휘/로컬 임베딩 캐시 | 사내 RAGaaS 또는 pgvector/Redis Vector, `VerifiedSqlCacheService` 의 `SemanticSimilarity` 교체 |
| `agent_conversation_turn` | Redis 세션 저장소 (TTL) |
| 콘솔 UI | 사내 포털 임베드 (API 그대로) |
| `agent.demo.seed` | `false` |
| `AGENT_LLM_MODE=local` (Mac mini) | 사내 LLM 게이트웨이 (동일 OpenAI 호환) |
| 인증 없음 | SSO/게이트웨이 인증, 사용자 ID·권한 전달 (앱 Scope 바인딩) |

---

## 13. 설계 판단과 한계

**판단**
- LLM 호출을 "판단이 필요한 곳"으로 제한했습니다. 검증·실행·반복 제어·상한은 전부 코드입니다.
- 6단계에서 충분성 판단과 답변 생성을 합쳐 호출 1회로 처리합니다.
- 재사용은 유사도가 아니라 **대상·조건·파라미터 적합성**으로 확정합니다.
- 로컬 모델 JSON 이 깨질 때를 대비해 모든 단계에 규칙 기반 폴백을 두었습니다. 파이프라인이 멈추지 않고, 폴백 발생은 로그로 드러납니다.
- EX 는 "SQL 문장 동일"이 아니라 "결과셋 동일"로 판정합니다 (행 순서 무시, 별칭 차이 허용, 숫자 정규화).

**한계 / 다음 단계**
- 오프라인 규칙 구현은 한국어 데모 질의 패턴에 맞춰져 있습니다. 실제 품질은 로컬 LLM 모드에서 나오며, 골든셋을 늘려 회귀 기준선을 강화해야 합니다.
- 시맨틱 캐시는 현재 DB 스캔 + 코사인입니다. 후보가 수천 건이 되면 벡터 인덱스(pgvector/Redis)로 이전해야 합니다.
- 스트리밍 응답은 넣지 않았습니다. 검증 필수 환경에서는 `stream=false` + 비동기 폴링이 안전합니다.
- 다중 SQL 질의의 EX 는 "실행된 조회 중 예상 결과와 일치하는 것이 하나라도 있으면 Pass" 로 완화했습니다. 복합 질의의 전체 결과 합산 기준이 필요하면 `ResultSetComparator` 를 확장하세요.
- Jira/이메일/API 도구 확장은 `LlmGateway` 옆에 Tool Worker 를 추가하는 방식이 자연스럽습니다.

---

## 14. 참고

- Spring AI 1.0.0-M3 + Spring Boot 3.3.5 조합은 공식 지원 범위 밖이지만, 이 프로젝트는 기동·질의·평가 전체 경로를 테스트로 확인했습니다. (Spring AI 자동구성 대신 `ChatClientConfig` 에서 직접 빈 구성)
- H2 는 `MODE=MySQL;DATABASE_TO_LOWER=TRUE` 로 운영 MySQL 문법에 가깝게 맞췄습니다.

---

## 15. MCP API 확장과 양방향 Reshaping (2026-11-13 추가)

코드를 고치지 않고 **테이블 설정만으로 외부 MCP 도구를 늘리고**, 통신 과정의 로우데이터를
**선언적 룰(JSONPath)** 로 양방향 재가공하는 계층입니다.

### 15.1 방향 3가지

| direction | 원천 | 하는 일 | 예 |
|---|---|---|---|
| `OUTBOUND` | 호출 파라미터 | 외부 도구 호출 → 방대한 Raw 응답을 필요한 필드만 남겨 압축 | K8s Pod 목록 |
| `INBOUND` | 내부 DB (`source_query`) | 내부 로우를 MCP 스펙(`tools/call`)으로 변환해 외부로 발송 | Jira 작업이력 동기화 |
| `BIDIRECTIONAL` | 내부 DB | 위 둘을 한 번에: 로우 → 발송 → 응답에서 접수 결과만 보관 | 관제 알람 발송 |

방향과 무관하게 **변환 엔진은 하나**(`DataReshaper`)입니다. 입력 JSON 이 DB 로우든 외부 응답이든
같은 룰 문법으로 매핑합니다. 이것이 "양방향" 의 실체입니다.

### 15.2 테이블

**`svcm_tb_mcp_api_master`** — 도구 1개 = 행 1개. 이 행이 곧 "코드 없는 도구 정의" 입니다.

| 컬럼 | 설명 |
|---|---|
| `api_id` / `api_name` / `tool_name` | API 식별자, 표시명, MCP 도구명 |
| `direction` | `OUTBOUND` / `INBOUND` / `BIDIRECTIONAL` |
| `transport_type` / `endpoint_url` | `HTTP`/`MCP`/`SSE`(MCP tools/call), `REST`/`REST_API`(순수 REST), 엔드포인트. `{appId}` 경로변수 지원 (§16) |
| `http_method` / `headers_config` | REST 전용: `GET`/`POST`/`PUT`/`PATCH`/`DELETE`, 커스텀 헤더 JSON (§16) |
| `auth_type` / `auth_config` | `NONE`/`BEARER`/`API_KEY`/`BASIC`, **시크릿이 아니라 환경변수 이름** |
| `request_schema` | 입력 파라미터 JSON Schema (문서·검증용) |
| `request_reshaping_rule` | 요청/원천 로우 → MCP payload 룰 (CLOB) |
| `response_reshaping_rule` | Raw 응답 → 정제 payload 룰 (CLOB) |
| `source_query` | INBOUND 원천 SELECT (named parameter `:name` 지원) |
| `timeout_ms` / `active_yn` / `description` | 타임아웃, 활성 여부, 설명 |

**`svcm_tb_mcp_payload_log`** — 실행 1회 = 행 1회. 실패도 남깁니다.

| 컬럼 | 설명 |
|---|---|
| `call_id` / `run_id` / `api_id` / `direction` | 호출 식별자, 에이전트 실행 연결, 대상 API |
| `raw_payload_in` / `reshaped_payload_in` | 원본 입력(DB 로우 또는 파라미터)과 변환 결과 |
| `raw_payload_out` / `reshaped_payload_out` | 원본 응답과 정제 결과 |
| `status_code` / `duration_ms` / `error_message` | HTTP 상태(폴백이면 0), 소요시간, 사유 |
| `transport_type` / `http_method` | 실행에 실제로 쓰인 프로토콜과 메서드 (MCP 면 메서드 null) |

### 15.3 Reshaping 룰 문법

```json
{
  "version": "1.0",
  "mode": "COLLECTION",
  "rootPath": "$",
  "itemPath": "$.items[*]",
  "filter": [{"path": "$.status.phase", "op": "NE", "value": "Succeeded"}],
  "limit": 10,
  "dropNull": true,
  "fields": [
    {"target": "podName", "source": "$.metadata.name", "required": true},
    {"target": "node", "source": "$.spec.nodeName", "default": "unassigned"},
    {"target": "restarts", "source": "$.status.containerStatuses[*].restartCount", "aggregate": "SUM", "default": 0}
  ],
  "envelope": {"type": "MCP_TOOL_CALL", "tool": "k8s.pod.list", "argumentsKey": "items"}
}
```

| 키 | 값 | 의미 |
|---|---|---|
| `mode` | `OBJECT` / `COLLECTION` | 객체 1건 매핑 / 배열 항목마다 매핑 |
| `rootPath` / `itemPath` | JSONPath | 실제 데이터 위치 / 반복 경로 |
| `filter[].op` | `EQ` `NE` `IN` `CONTAINS` `EXISTS` `GT` `LT` | 항목 제외 조건 (전체 AND) |
| `limit` / `dropNull` | 숫자 / bool | 결과 개수 상한, null 필드 생략 |
| `fields[].source` | JSONPath | 항목 기준 상대 경로 |
| `fields[].constant` | JSON | 원천 없이 고정값 (예: `severity = "HIGH"`) |
| `fields[].transform` | `TRIM` `UPPER` `LOWER` `TO_STRING` `TO_NUMBER` `TO_INT` `TO_BOOLEAN` | 값 변환 |
| `fields[].aggregate` | `SUM` `COUNT` `AVG` `MIN` `MAX` | 여러 값을 하나로 집계 |
| `fields[].default` / `required` | JSON / bool | 원천 부재 시 대체값 / 실패 처리 |
| `envelope` | `MCP_TOOL_CALL` / `REST_JSON` | 결과를 전송 규격으로 감싸기. MCP 는 `{tool, arguments}`, REST 는 `{payloadKey: [...], attributes:{...}}` (§16) |

룰 오류(깨진 JSON, 잘못된 JSONPath, 필수 필드 누락)는 조용히 넘기지 않고 **필드명과 경로를 포함한 400** 으로 돌아옵니다.

### 15.4 실행 파이프라인 (IPO)

```
POST /api/v1/mcp/execute/{apiId}
   │
[1] 기준정보 로드 ── svcm_tb_mcp_api_master (방향·룰·엔드포인트·타임아웃)
   │
[2] 요청 Reshaping ── INBOUND: source_query 로우 → DataReshaper → tools/call payload
   │                 OUTBOUND: 호출 파라미터 → arguments 정리
[3] 통신 ─────────── McpClientGateway (MCP Streamable HTTP tools/call, JSON/SSE 응답, 인증)
   │
[4] 응답 Reshaping ── Raw 응답 → DataReshaper → 필요한 필드만 남긴 압축 JSON
   │
[5] 감사로그 ─────── raw/reshaped 4종 + 상태·소요시간을 1행으로 (실패 포함)
   ▼
McpExecutionResult (status, request, response, stages, warnings, logId)
```

`McpOrchestratorService` 에는 이 5단계 흐름만 있고, JSONPath 는 `DataReshaper`,
HTTP 는 `McpClientGateway`, 원천 SQL 은 `McpSourceQueryReader` 가 맡습니다.

### 15.5 사용 예

```bash
# 등록된 도구 조회 (데모 시드 3건)
curl -s http://localhost:8080/api/v1/mcp/apis

# OUTBOUND 실행 — K8s Raw 6.5KB → Pod 3건 × 6필드
curl -s -X POST http://localhost:8080/api/v1/mcp/execute/MCP-K8S-001 \
  -H 'Content-Type: application/json' \
  -d '{"params":{"namespace":"svcm-portal"},"runId":"RUN-20261113205318"}'
```

```json
{
  "callId": "MCP-20261113205318-2572",
  "logId": 1,
  "apiId": "MCP-K8S-001",
  "direction": "OUTBOUND",
  "status": "FALLBACK",
  "statusCode": 0,
  "durationMs": 129,
  "request": {"namespace": "svcm-portal", "labelSelector": "app=svcm-portal", "limit": 20},
  "response": [
    {"podName": "svcm-portal-7d9f8c6b5-2xk4p", "namespace": "svcm-portal", "phase": "Running",
     "node": "macmini-node-01", "restarts": 3, "image": "registry.local/svcm/portal:2.5.1"},
    {"podName": "svcm-portal-7d9f8c6b5-9m2vq", "namespace": "svcm-portal", "phase": "Failed",
     "node": "macmini-node-02", "restarts": 7, "image": "registry.local/svcm/portal:2.5.1"},
    {"podName": "svcm-portal-7d9f8c6b5-kd8zt", "namespace": "svcm-portal", "phase": "Pending",
     "node": "unassigned", "restarts": 0, "image": "registry.local/svcm/portal:2.5.1"}
  ],
  "stages": [
    "1 기준정보: MCP-K8S-001 (OUTBOUND, HTTP)",
    "2 요청 Reshaping: 호출 파라미터 → MCP arguments 필드 3개",
    "3 통신: k8s.pod.list → http://localhost:18080/mcp/k8s (오프라인 픽스처) 2ms",
    "4 응답 Reshaping: raw 6537자 → 결과 3건, 제외 1건",
    "5 감사로그: logId=1, status=FALLBACK"
  ],
  "warnings": ["OFFLINE_FALLBACK ConnectException"],
  "error": "OFFLINE_FALLBACK ConnectException"
}
```

```bash
# INBOUND 실행 — Jira 뷰 6건을 entries[] 로 변환해 발송
curl -s -X POST http://localhost:8080/api/v1/mcp/execute/MCP-JIRA-001 \
  -H 'Content-Type: application/json' -d '{}'

# 새 도구 등록 (코드 배포 없이)
curl -s -X POST http://localhost:8080/api/v1/mcp/apis -H 'Content-Type: application/json' -d '{
  "apiId":"MCP-CUSTOM-001","apiName":"사내 위키 검색","toolName":"wiki.search","direction":"OUTBOUND",
  "endpointUrl":"http://localhost:18080/mcp/wiki",
  "responseReshapingRule":"{\"mode\":\"COLLECTION\",\"itemPath\":\"$.pages[*]\",\"fields\":[{\"target\":\"title\",\"source\":\"$.title\"},{\"target\":\"url\",\"source\":\"$.links.self\"}]}"
}'

# 감사 로그 (raw ↔ reshaped 비교)
curl -s 'http://localhost:8080/api/v1/mcp/logs?apiId=MCP-K8S-001&limit=5'
```

### 15.6 데모 시드 (`data.sql`)

| api_id | 방향 | 원천 / 대상 | 룰이 하는 일 |
|---|---|---|---|
| `MCP-K8S-001` | OUTBOUND | K8s `k8s.pod.list` | 6.5KB Raw → Pod 3건 × 6필드, 컨테이너 restartCount 합산, `Succeeded` 제외, node 없으면 `unassigned` |
| `MCP-JIRA-001` | INBOUND | Jira 뷰 `/ `jira.worklog.sync` | 6로우 → `entries[]`, snake_case→camelCase, `hours` 숫자화, summary trim, 접수 결과만 보관 |
| `MCP-ALERT-001` | BIDIRECTIONAL | CRITICAL 자산 → `monitor.alert.push` | 2건 알람 스펙 변환, `accepted/channel/messageId` 만 정제 |

### 15.7 통신 모드와 폴백

| `agent.mcp.transport-mode` | 동작 |
|---|---|
| `AUTO` (기본) | 실제 호출 시도 → **연결 자체가 실패하면** 결정적 픽스처로 대체 (`status=FALLBACK`) |
| `OFFLINE` | 네트워크 없이 픽스처만 (데모·테스트) |
| `HTTP` | 실제 호출만. 실패는 숨기지 않고 502 `MCP_TRANSPORT_ERROR` |

- **HTTP 4xx/5xx 는 폴백하지 않습니다.** 서버가 응답한 오류는 `status=FAILED` + `error_message=HTTP nnn` 으로 남습니다. (가짜 성공으로 덮지 않음)
- `FALLBACK` 은 "데모 데이터로 흐름을 검증했다" 는 뜻이며, **외부 시스템에 실제 반영된 것이 아닙니다.**
- 폴백 사유는 `warnings` 와 `error_message` 에 `OFFLINE_FALLBACK <예외>` 로 남습니다.

### 15.8 보안·운영 주의

- `auth_config` 에는 시크릿을 저장하지 않습니다. `{"header":"X-Api-Key","envVar":"MCP_JIRA_API_KEY"}` 처럼 **환경변수 이름**만 두고, 값은 프로세스 환경/시크릿 매니저에서 주입합니다. 값은 요청·응답·감사로그 어디에도 기록하지 않습니다.
- `source_query` 는 `SELECT`/`WITH` 단일 문장만 허용하고, 값은 named parameter 로만 바인딩합니다. 결과는 `agent.mcp.max-source-rows` 로 제한합니다.
- `endpoint_url` 은 `http(s)://` 만 허용합니다. 운영에서는 아웃바운드 허용 목록(egress policy)과 함께 쓰는 것을 권장합니다.
- 감사 로그 payload 는 `agent.mcp.max-log-payload-chars`(기본 20,000자)에서 잘리고 `...(truncated n chars)` 로 표시됩니다.
- 운영에서는 `AGENT_MCP_TRANSPORT_MODE=HTTP` 로 두어 폴백을 끄고, 실패를 그대로 드러내는 편이 안전합니다.

### 15.9 한계

- 룰은 항목 단위 매핑·필터·집계까지 지원합니다. 조인이나 조건부 분기(if/else)는 룰로 표현하지 않고, 필요하면 `DataReshaper` 에 노드를 추가하는 편이 읽기 쉽습니다.
- 응답이 매우 큰 경우(수 MB) 원본은 로그에서 잘립니다. 원본 보관이 필요하면 Object Storage 로 분리해야 합니다.
- MCP 리소스(`resources/list`)·프롬프트 기능은 다루지 않고 `tools/call` 만 사용합니다.
- 에이전트 파이프라인(`IncidentQueryFlowService`)과의 자동 연결은 아직 없습니다. MCP 실행은 현재 관리/실행 API 로 호출하며, 답변 컨텍스트에 넣으려면 `PipelineContext` 에 MCP 실행 단계를 추가하면 됩니다.

---

## 16. REST API + MCP 테이블 기반 통합 확장 (2026-11-14 추가)

§15 까지의 MCP 어댑터를 **프로토콜 하나 더**로 넓힌 것이 아니라, "전송 방식도 테이블 데이터" 로
일반화한 단계입니다. 같은 테이블 한 행에서 `transport_type` 과 `http_method` 만 바꾸면
MCP(JSON-RPC `tools/call`)로도, 순수 REST API 로도 동작합니다. **Java 코드의 분기는 한 줄**입니다.

```java
return api.isRestTransport()
        ? restGateway.call(api, request, params)   // REST / REST_API
        : gateway.call(api, request);              // MCP / HTTP / SSE
```

### 16.1 MCP 와 REST 의 차이 (테이블 값이 정한다)

| 항목 | MCP (`transport_type=MCP/HTTP/SSE`) | REST (`transport_type=REST/REST_API`) |
|---|---|---|
| 게이트웨이 | `McpClientGateway` | `RestApiClientGateway` |
| 요청 본문 | JSON-RPC 2.0 `tools/call` 봉투 | 없음(순수 JSON). 봉투를 만들지 않는다 |
| HTTP 메서드 | 항상 `POST` | `http_method` (`GET` `POST` `PUT` `PATCH` `DELETE`) |
| 파라미터 전달 | `params.arguments` 객체 | 경로변수 `{appId}` 치환 + (GET/DELETE) Query String + JSON 본문 |
| 커스텀 헤더 | 없음 (인증 헤더만) | `headers_config` JSON 객체 |
| 응답 해석 | `result.content[].text` 까지 벗김 (JSON/SSE) | 봉투 없음. JSON 그대로 (정제는 룰이) |
| 룰 envelope | `{"type":"MCP_TOOL_CALL","tool":...,"argumentsKey":...}` | `{"type":"REST_JSON","payloadKey":"incidents","attributes":{"source":"gsre-agent"}}` |

두 게이트웨이는 같은 `McpCallResult` 를 돌려주므로, **Orchestrator·DataReshaper·감사로그·픽스처 폴백은
한 벌만** 있습니다. 새 프로토콜을 더할 때도 게이트웨이 하나와 분기 한 줄이면 됩니다.

### 16.2 스키마 추가 컬럼

`svcm_tb_mcp_api_master`

| 컬럼 | 기본값 | 설명 |
|---|---|---|
| `transport_type` | `HTTP` | `MCP` / `HTTP` / `SSE` (MCP tools/call) · `REST` / `REST_API` (순수 REST) |
| `http_method` | `POST` | REST 전용. `GET`·`DELETE` 는 본문 없이 남은 파라미터를 Query String 으로 보낸다 |
| `headers_config` | null | REST 전용. 요청에 붙일 헤더 JSON. **시크릿은 쓰지 않는다** (`auth_config` 참조) |

`svcm_tb_mcp_payload_log`

| 컬럼 | 설명 |
|---|---|
| `transport_type` / `http_method` | 이번 실행에 실제로 쓰인 프로토콜/메서드 (MCP 면 `http_method` 는 null) |

기존 `agent-db` 파일을 쓰던 환경은 `schema.sql` 맨 끝의 1회성 `ALTER TABLE` 문(4줄)을 H2 콘솔에서
실행하거나 파일을 지우고 다시 기동하면 됩니다. MySQL 운영 DDL 은 같은 블록 주석을 참고하세요.

### 16.3 REST 시드 3건 (`data.sql`)

기존 MCP 시드 3건(`MCP-K8S-001`, `MCP-JIRA-001`, `MCP-ALERT-001`)은 그대로 두고, 실제 G-SRE 도메인
테이블에 붙는 REST 3건을 추가했습니다.

| api_id | 방향 | 프로토콜 | 원천 / 대상 | 룰이 하는 일 |
|---|---|---|---|---|
| `REST-CMDB-001` | OUTBOUND | `GET /api/cmdb/apps/{appId}` | CMDB 앱 상세 | `{appId}` 경로변수 + `include`,`depth` Query String. 수 KB 원본(owners/deps/metrics/auditLog) → 앱ID·앱명·담당자·부서·티어·상태·열린장애 11필드 |
| `REST-INCIDENT-001` | INBOUND | `POST /api/incidents/sync` | `svcm_tb_jira_issue` (issue_type=`INCIDENT`) `LEFT JOIN` 앱/사용자 | 5건 → `{"source":"gsre-agent","schemaVersion":"1.0","incidents":[...]}` (JSON-RPC 봉투 없음) → 응답의 `syncedCount/batchId/status` 만 보관 |
| `REST-NOTIFY-001` | BIDIRECTIONAL | `POST /api/v1/webhooks/sre-alert` | `svcm_tb_app_master` CRITICAL·OPERATING + 열린 장애 수 | 2건 → `{"source":"gsre-agent","eventType":"asset.health","alerts":[{appId,severity,openIncidents}]}` → `notified/channel/receiptId` 정제. `API_KEY`(`MCP_ALERT_API_KEY`) 헤더 |

`source_query` 는 `schema.sql` 에 실제로 있는 테이블·컬럼만 사용합니다. (`svcm_tb_app_master`,
`svcm_tb_jira_issue`, `svcm_tb_user_info_master`)

### 16.4 사용 예

```bash
# CMDB 상세 (GET): appId 는 경로변수, 나머지는 Query String
curl -s -X POST http://localhost:8080/api/v1/mcp/execute/REST-CMDB-001 \
  -H 'Content-Type: application/json' \
  -d '{"params":{"appId":"APP-1001"},"runId":"RUN-20261114093000"}'
```

```json
{
  "apiId": "REST-CMDB-001",
  "transportType": "REST",
  "httpMethod": "GET",
  "status": "SUCCESS",
  "statusCode": 200,
  "request": {"appId": "APP-1001", "include": "owners,deps,metrics", "depth": 2},
  "response": {
    "appId": "APP-1001", "appName": "서비스맵포털", "appType": "WEB", "serviceName": "서비스맵포털",
    "owner": "김민수", "ownerDept": "SRE플랫폼팀", "tier": "TIER-1",
    "criticality": "CRITICAL", "status": "OPERATING", "openIncidents": 1, "restartCount": 9
  },
  "stages": [
    "1 기준정보: REST-CMDB-001 (OUTBOUND, REST, GET)",
    "2 요청 Reshaping: 호출 파라미터 → REST GET 파라미터 필드 3개",
    "3 통신: cmdb.app.get → GET http://localhost:18080/api/cmdb/apps/{appId} [200] 87ms",
    "4 응답 Reshaping: raw 1687자 → 결과 11건",
    "5 감사로그: logId=12, status=SUCCESS"
  ]
}
```

```bash
# 장애 이력 동기화 (POST, 순수 REST JSON)
curl -s -X POST http://localhost:8080/api/v1/mcp/execute/REST-INCIDENT-001 \
  -H 'Content-Type: application/json' -d '{}'
# → 요청 본문: {"source":"gsre-agent","schemaVersion":"1.0","incidents":[{...} × 5]}
# → 응답     : {"syncedCount":5,"batchId":"INC-BATCH-261114-0001","status":"ACCEPTED"}

# SRE 알람 Webhook (BIDIRECTIONAL)
curl -s -X POST http://localhost:8080/api/v1/mcp/execute/REST-NOTIFY-001 \
  -H 'Content-Type: application/json' -d '{}'

# 새 REST API 등록 (코드 배포 없이). httpMethod/headersConfig 가 그대로 전송 방식을 바꾼다.
curl -s -X POST http://localhost:8080/api/v1/mcp/apis -H 'Content-Type: application/json' -d '{
  "apiId":"REST-CMDB-002","apiName":"CMDB 앱 목록","toolName":"cmdb.app.list",
  "direction":"OUTBOUND","transportType":"REST","httpMethod":"GET",
  "endpointUrl":"http://localhost:18080/api/cmdb/apps",
  "headersConfig":"{\"X-GSRE-Source\":\"incident-agent-spring-ai\"}",
  "responseReshapingRule":"{\"mode\":\"COLLECTION\",\"rootPath\":\"$.data.items\",\"itemPath\":\"$[*]\",\"fields\":[{\"target\":\"appId\",\"source\":\"$.appId\"},{\"target\":\"appName\",\"source\":\"$.appName\"}]}"
}'
```

### 16.5 전송 규칙 (게이트웨이 동작)

- **경로변수**: `endpoint_url` 의 `{name}` 을 `params`(또는 Reshaping 결과) 값으로 치환합니다. 값은
  경로 세그먼트로 URL 인코딩되고(공백 → `%20`), 값을 찾지 못하면 502 `MCP_TRANSPORT_ERROR` 로
  드러납니다(조용히 빈 값으로 호출하지 않음).
- **Query String**: `GET`/`DELETE` 는 본문을 보내지 않고, 경로변수로 쓰인 키를 뺀 나머지 파라미터를
  `k=v` 로 붙입니다. 배열 값은 `a,b,c` 로 합칩니다.
- **본문**: `POST`/`PUT`/`PATCH` 는 Reshaping 결과 JSON 을 `Content-Type: application/json` 으로 보냅니다.
- **헤더**: `headers_config` → `auth_config`(환경변수 기반 BEARER/API_KEY/BASIC) 순으로 붙습니다.
  같은 이름이면 인증 헤더가 이깁니다. 인증 값은 요청·응답·감사로그 어디에도 남지 않습니다.
- **등록 검증(400)**: 지원하지 않는 `http_method`, JSON 객체가 아닌 `headers_config`,
  본문 없는 `GET`/`DELETE` 로 `INBOUND`/`BIDIRECTIONAL` 을 등록하는 경우.
- **실패**: AUTO 모드는 연결 자체가 실패할 때만 결정적 픽스처로 대체(`FALLBACK`)하고,
  HTTP 4xx/5xx 는 `FAILED` + `error_message=HTTP nnn` 으로 남깁니다. HTTP 모드는 폴백 없음.

### 16.6 검증

`RestApiIntegrationTest` (12) 가 embedded `com.sun.net.httpserver.HttpServer` 를 시드에 적힌 포트(18080)에
띄우고 `agent.mcp.transport-mode=HTTP` 로 실제 통신하며 서버 쪽에서 다음을 확인합니다.

- GET 의 메서드·경로(`/api/cmdb/apps/APP%201003`)·Query String·커스텀 헤더·본문 없음
- POST 본문에 `jsonrpc`/`tools/call` 이 없고 `source`,`incidents[]` 만 있는지 (5건, INCIDENT 만)
- BIDIRECTIONAL webhook 의 `alerts[]` 2건과 `notified/channel/receiptId` 정제
- `PUT` 등록 직후 그 메서드/헤더로 나가는지, `DELETE` 는 Query String·`PATCH` 는 JSON 본문으로 나가는지
- HTTP 500 이 FALLBACK 으로 덮이지 않는지, 오프라인 픽스처가 결정적인지
- 감사 로그의 `transport_type`/`http_method` 기록

전체 **50개 테스트 통과** (기존 38개 + REST 12개).

### 16.7 한계

- 오프라인 픽스처는 결정적이므로 경로변수를 바꿔도 같은 Raw(APP-1001 기준)를 돌려줍니다. 실제 값
  분기는 HTTP 모드에서만 일어납니다.
- `PATCH`/`PUT` 의 부분 갱신 규약(JSON Merge Patch 등)은 룰로 본문을 만드는 수준까지만 다룹니다.
- OAuth2 클라이언트 크리덴셜 토큰 발급·갱신은 아직 없습니다. `auth_type` 은 `BEARER`/`API_KEY`/`BASIC`
  (환경변수 주입) 3종이며, 토큰 자동 갱신이 필요하면 `McpAuthHeaders` 에 한 곳만 추가하면 됩니다.
- REST 응답이 `text/event-stream`/XML 인 경우 본문을 텍스트로 넘깁니다. 구조화가 필요하면 룰 대신
  파서를 게이트웨이에 추가해야 합니다.
