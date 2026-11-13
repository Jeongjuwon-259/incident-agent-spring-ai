# incident-agent-spring-ai

G-SRE 서비스맵 포털 **장애 지원 AI Agent** — Java 21 / Spring Boot 3.3.5 / Spring AI 1.0.0-M3 (OpenAI-compatible)

자연어 질의를 받아 **6단계 명시적 상태머신**으로 조회 계획 → 검증 SQL 시맨틱 캐시(RAG) → 재사용 판단 → NL2SQL → 검증·실행·Self-Repair → 근거 기반 답변까지 처리하고, **EX/AA/FN/실행 성공** 4개 지표의 품질 평가 하네스로 회귀 검증하는 백엔드입니다.

- Flowise/LangGraph 없이 **Java 코드로 흐름을 명시적으로 제어**합니다.
- Mac mini 로컬 LLM(Ollama, vLLM, llama.cpp, LM Studio, Qwen, Muse …)을 **OpenAI 호환 엔드포인트**로 붙입니다. 주소/모델은 환경변수 한 줄로 교체됩니다.
- 모델이 없어도 `AGENT_LLM_MODE=offline` 으로 **전체 파이프라인·감사로그·품질평가가 끝까지 동작**합니다 (규칙 기반 결정적 구현). 테스트 22개가 이 모드로 실행됩니다.
- H2 임베디드 DB + 시드 데이터(8대 테이블, 검증 SQL 6건, 골든셋 6건)로 받자마자 확인 가능합니다.

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
├── audit         AuditRepository(실행/단계/LLM 이력), ConversationStore(멀티턴)
└── web           AgentController, EvalController, CacheController, WebMapper, DTO
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
- `schema.sql` — H2 스키마 (업무 8테이블 + Jira 원장/뷰 + 감사 + 캐시 + 평가)
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

현재 **22개 테스트 전부 통과** (오프라인 모드, H2 in-memory).

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
