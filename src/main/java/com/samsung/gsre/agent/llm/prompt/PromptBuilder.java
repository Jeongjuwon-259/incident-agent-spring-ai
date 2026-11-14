package com.samsung.gsre.agent.llm.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.samsung.gsre.agent.config.AgentProperties;
import com.samsung.gsre.agent.llm.support.JsonSupport;
import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.Intent;
import com.samsung.gsre.agent.model.JudgeVerdict;
import com.samsung.gsre.agent.model.LookupResult;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.PreparedSql;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import com.samsung.gsre.agent.model.SqlOrigin;
import com.samsung.gsre.agent.nl2sql.SchemaCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 로컬 LLM 프롬프트 생성과 응답 파싱을 한 곳에 모은다.
 *
 * <p>모든 단계는 "JSON 하나만 출력" 을 요구하고, 파싱에 실패하면 호출한 쪽이
 * 규칙 기반 폴백으로 넘어갈 수 있도록 {@code null} 을 돌려준다.</p>
 *
 * <h2>IPO 배치 (이 클래스의 규칙)</h2>
 * <p>단계마다 <b>프롬프트 생성(INPUT)</b> → <b>응답 파싱(OUTPUT)</b> 한 쌍을 같은 순서로 배치한다.
 * 단계 순서(1→3→4→6→평가)가 곧 흐름 순서이므로, 클래스 전체를 INPUT/OUTPUT 두 덩어리로
 * 재정렬하지 않는다. 공통 렌더링 유틸은 맨 아래에 둔다.</p>
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final SchemaCatalog catalog;
    private final AgentProperties props;

    // ------------------------------------------------------------------
    // 1단계: 질의 이해
    // ------------------------------------------------------------------

    public LlmPrompt understand(String question, List<ConversationTurn> history) {
        String system = """
                너는 삼성 G-SRE 서비스맵 포털 '장애 지원 AI Agent'의 질의 이해 모듈이다.
                사용자의 자연어 질의와 이전 대화 맥락을 분석해 조회 계획을 JSON 하나로만 출력한다.

                [규칙]
                - 출력은 JSON 객체 하나. 설명, 인사말, 마크다운 코드펜스를 붙이지 않는다.
                - 이전 대화를 이어받는 질의면 follow_up=true 로 하고, 이전 턴의 APP_ID/부서/사용자를 normalized_question 에 반영한다.
                - 하나의 SQL 로 답할 수 있으면 tasks 는 1개만 만든다. 서로 다른 대상(예: 담당자 + 사용자 수)을 함께 물을 때만 2~3개로 나눈다.
                - app_ids 에는 APP-1001 같은 자산 ID만 넣는다. 확실하지 않으면 빈 배열로 둔다.
                - filters 에는 질의에서 확정된 조건만 넣는다 (appId, deptName, userName, aliasName, issueType, days).
                - 추측으로 조건을 만들지 않는다.

                [intent]
                APP_INFO(앱 기본정보), CONTACT(담당자/연락처), USER_ACCESS(사용자·권한), DEPT(부서/부서장),
                SERVICE_MAP(서비스맵/도메인), JIRA_WORKLOG(Jira 작업이력), ALIAS(APP 별칭), COUNT(건수), GENERAL

                [출력 스키마]
                {"normalized_question":"...","intent":"CONTACT","app_ids":["APP-1001"],"keywords":["담당자"],
                 "multi_step":false,"follow_up":false,"reasoning":"...",
                 "tasks":[{"id":"T1","purpose":"APP-1001 담당자 조회","intent":"CONTACT",
                           "target_tables":["svcm_tb_app_master","svcm_tb_app_user_map"],"filters":{"appId":"APP-1001"}}]}

                [조회 가능한 테이블]
                """ + catalog.renderForPrompt();

        String user = """
                [이전 대화]
                %s

                [현재 질의]
                %s
                """.formatted(historyBlock(history), question);

        return new LlmPrompt(system, user, 0.0, 700);
    }

    public QueryPlan parsePlan(String raw) {
        JsonNode node = JsonSupport.parseObject(raw);
        if (node == null) {
            return null;
        }
        Intent intent = parseIntent(JsonSupport.text(node, "intent"));
        List<String> appIds = stringList(node.get("app_ids"));
        List<String> keywords = stringList(node.get("keywords"));
        List<LookupTask> tasks = new ArrayList<>();
        JsonNode taskNodes = node.get("tasks");
        if (taskNodes != null && taskNodes.isArray()) {
            int i = 1;
            for (JsonNode t : taskNodes) {
                Intent taskIntent = parseIntent(JsonSupport.text(t, "intent"));
                if (taskIntent == null) {
                    taskIntent = intent == null ? Intent.GENERAL : intent;
                }
                tasks.add(new LookupTask(
                        JsonSupport.text(t, "id") == null ? "T" + i : JsonSupport.text(t, "id"),
                        JsonSupport.text(t, "purpose") == null ? "조회" : JsonSupport.text(t, "purpose"),
                        taskIntent,
                        stringList(t.get("target_tables")),
                        stringMap(t.get("filters"))));
                i++;
            }
        }
        if (tasks.isEmpty()) {
            return null;
        }
        String normalized = JsonSupport.text(node, "normalized_question");
        return new QueryPlan(
                normalized == null ? "" : normalized,
                intent == null ? tasks.get(0).intent() : intent,
                appIds,
                keywords,
                tasks,
                JsonSupport.bool(node, "multi_step", tasks.size() > 1),
                JsonSupport.bool(node, "follow_up", false),
                JsonSupport.text(node, "reasoning"));
    }

    // ------------------------------------------------------------------
    // 3단계: SQL 재사용 판단
    // ------------------------------------------------------------------

    public LlmPrompt reuse(String question, LookupTask task, List<CacheCandidate> candidates) {
        String system = """
                너는 검증된 SQL 재사용 판단 모듈이다.
                현재 조회 작업을 후보 SQL 중 하나로 처리할 수 있는지 판단해 JSON 하나로만 출력한다.

                [판단 기준]
                - 유사도 점수만으로 재사용을 확정하지 않는다.
                - 조회 대상(테이블)과 조회 목적이 같아야 한다.
                - WHERE 조건(대상 APP, 기간, 상태, role)이 같거나, 파라미터만 바꾸면 맞는 경우에만 재사용한다.
                - 필요한 결과 컬럼이 후보 SQL 에 모두 있어야 한다.
                - 조건이 다르면 reuse=false 로 하고 이유를 적는다.

                [출력 스키마]
                {"reuse":true,"candidate_id":1,"reason":"...","parameters":{"appId":"APP-1001"}}
                재사용하지 않을 때는 {"reuse":false,"candidate_id":null,"reason":"...","parameters":{}}.
                후보가 없으면 reuse=false.
                """;

        String user = """
                [현재 질의]
                %s

                [조회 작업]
                purpose=%s / intent=%s / filters=%s

                [검증 SQL 후보]
                %s
                """.formatted(question, task.purpose(), task.intent(), task.filters(), candidateBlock(candidates));

        return new LlmPrompt(system, user, 0.0, 500);
    }

    public ReuseDecision parseReuse(String raw, List<CacheCandidate> candidates) {
        JsonNode node = JsonSupport.parseObject(raw);
        if (node == null) {
            return null;
        }
        boolean reuse = JsonSupport.bool(node, "reuse", false);
        if (!reuse) {
            return ReuseDecision.reject(JsonSupport.text(node, "reason"));
        }
        long id = node.hasNonNull("candidate_id") ? node.get("candidate_id").asLong(-1) : -1;
        CacheCandidate chosen = candidates.stream().filter(c -> c.id() == id).findFirst().orElse(null);
        if (chosen == null) {
            return ReuseDecision.reject("후보 ID 를 확인할 수 없어 재사용하지 않습니다.");
        }
        return ReuseDecision.accept(chosen.id(),
                JsonSupport.text(node, "reason") == null ? "검증 SQL 재사용" : JsonSupport.text(node, "reason"),
                objectMap(node.get("parameters")));
    }

    // ------------------------------------------------------------------
    // 4단계: SQL 준비 (신규 생성 / Self-Repair 재생성)
    // ------------------------------------------------------------------

    public LlmPrompt nl2sql(String question, LookupTask task, List<CacheCandidate> similar) {
        String system = """
                너는 G-SRE 서비스맵 포털 조회용 NL2SQL 생성기다. MySQL 문법의 SELECT 문 하나만 만들어 JSON 으로 출력한다.

                [절대 규칙]
                - SELECT 또는 WITH ... SELECT 만 사용한다. INSERT/UPDATE/DELETE/DDL 은 생성하지 않는다.
                - 아래 메타데이터에 있는 테이블만 사용한다. 테이블 이름에 스키마 접두어를 붙이지 않는다.
                - 사용자 입력값은 SQL 문자열에 직접 넣지 말고 :appId, :deptName 같은 이름 있는 파라미터로 분리한다.
                - LIMIT 을 반드시 붙인다. 기본 50, 최대 200.
                - SELECT * 는 쓰지 않는다. 필요한 컬럼만 명시한다.
                - 개인정보(이메일/연락처/사번)는 요청 목적에 필요할 때만 조회한다.
                - 컬럼/테이블 이름은 메타데이터에 있는 것만 사용한다. 추측해서 만들지 않는다.
                - 결과가 없을 수 있는 조인은 LEFT JOIN 을 사용한다.

                [비즈니스 규칙]
                - APP 담당자 = svcm_tb_app_user_map.role_code = 'OWNER' 인 사용자
                - 장애 = svcm_tb_jira_issue.issue_type = 'INCIDENT' (뷰: svcm_vw_jira_dri_worklog)
                - 최근 작업이력 = worklog_date 내림차순
                - 부서장 = svcm_tb_dept_info_master.dept_head_name

                [출력 스키마]
                {"sql":"SELECT ... WHERE a.app_id = :appId LIMIT 50","parameters":{"appId":"APP-1001"},
                 "tables":["svcm_tb_app_master"],"notes":"..."}
                JSON 외 다른 텍스트를 출력하지 않는다.

                [메타데이터]
                """ + catalog.renderForPrompt();

        String user = """
                [사용자 질의]
                %s

                [이번에 처리할 조회 작업]
                id=%s
                purpose=%s
                intent=%s
                filters=%s

                [참고할 유사 검증 SQL (재사용 불가 판정됨)]
                %s
                """.formatted(question, task.id(), task.purpose(), task.intent(), task.filters(),
                candidateBlock(similar));

        return new LlmPrompt(system, user, 0.0, 800);
    }

    public LlmPrompt repair(PreparedSql failed, String errorMessage, LookupTask task,
                            List<CacheCandidate> similar) {
        String system = """
                너는 SQL Self-Repair 모듈이다. 실행에 실패한 SQL 과 오류 메시지를 보고 수정된 SQL 을 JSON 하나로 출력한다.

                [수정 원칙]
                - 오류 원인만 최소한으로 고친다. 조회 목적과 조건은 바꾸지 않는다.
                - 존재하지 않는 컬럼/테이블은 메타데이터의 실제 이름으로 바꾼다.
                - SELECT * 는 필요한 컬럼 목록으로 바꾼다.
                - 문법 오류(따옴표, 콤마, JOIN 조건 누락, LIMIT 누락)를 바로잡는다.
                - SELECT 만 사용하고, 허용된 테이블만 쓴다.
                - 같은 오류가 반복되면 억지로 고치지 말고 notes 에 원인을 적는다.

                [출력 스키마]
                {"sql":"SELECT ...","parameters":{},"tables":[],"notes":"수정 내용"}

                [메타데이터]
                """ + catalog.renderForPrompt();

        String user = """
                [조회 작업]
                purpose=%s / intent=%s / filters=%s

                [실패한 SQL]
                %s

                [바인딩 파라미터]
                %s

                [오류 메시지]
                %s

                [참고 유사 SQL]
                %s
                """.formatted(task.purpose(), task.intent(), task.filters(), failed.sql(),
                JsonSupport.write(failed.parameters()), errorMessage, candidateBlock(similar));

        return new LlmPrompt(system, user, 0.0, 800);
    }

    public PreparedSql parsePrepared(String raw, SqlOrigin origin, Long cacheId) {
        JsonNode node = JsonSupport.parseObject(raw);
        if (node == null) {
            return null;
        }
        String sql = JsonSupport.text(node, "sql");
        if (sql == null || sql.isBlank()) {
            return null;
        }
        return new PreparedSql(sql.trim(), objectMap(node.get("parameters")),
                stringList(node.get("tables")), origin, cacheId, JsonSupport.text(node, "notes"));
    }

    // ------------------------------------------------------------------
    // 6단계: 결과 확인 + 최종 답변 생성 (한 번의 호출)
    // ------------------------------------------------------------------

    public LlmPrompt answer(String question, List<LookupTask> plannedTasks,
                            List<LookupResult> results, List<ConversationTurn> history) {
        String system = """
                너는 G-SRE 장애 지원 AI Agent 의 최종 답변 생성기다.
                누적된 SQL 조회 결과가 질문을 충족하는지 판단하고, 충족하면 답변을 만든다. JSON 하나로만 출력한다.

                [답변 규칙]
                - 한국어 존댓말. 장애 대응 담당자가 바로 쓸 수 있게 핵심 값(이름, 부서, 연락처, 건수, 일시)을 먼저 쓴다.
                - 조회 결과에 있는 사실만 말한다. 추측, 일반론, 사과문, 자기소개를 넣지 않는다.
                - SQL 문장이나 테이블 이름을 답변에 노출하지 않는다.
                - 결과가 비어 있으면 "조회 결과가 없습니다" 라고 명확히 말한다.
                - 일부 조회가 실패했으면 무엇이 실패했는지 밝히고, 확인된 것만 답한다.
                - 질문이 여러 건을 요구하는데 일부만 조회됐으면 sufficient=false 로 하고 missing_facts 에 빠진 항목을 적는다.
                - 추가 조회가 필요하면 next_purposes 에 조회 목적을 적는다. 필요 없으면 빈 배열.

                [출력 스키마]
                {"sufficient":true,"answer":"...","missing_facts":[],"next_purposes":[]}
                """ ;

        String user = """
                [이전 대화]
                %s

                [현재 질의]
                %s

                [계획된 조회 작업]
                %s

                [조회 결과]
                %s
                """.formatted(historyBlock(history), question, taskBlock(plannedTasks),
                resultContext(results, props.getEval().getJudgeTruncateRows()));

        return new LlmPrompt(system, user, 0.0, 900);
    }

    public AnswerOutcome parseAnswer(String raw) {
        JsonNode node = JsonSupport.parseObject(raw);
        if (node == null) {
            return null;
        }
        String answer = JsonSupport.text(node, "answer");
        if (answer == null || answer.isBlank()) {
            return null;
        }
        return new AnswerOutcome(JsonSupport.bool(node, "sufficient", true), answer,
                stringList(node.get("missing_facts")), stringList(node.get("next_purposes")));
    }

    // ------------------------------------------------------------------
    // 품질 평가 (LLM-as-a-Judge)
    // ------------------------------------------------------------------

    public LlmPrompt judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
        String system = """
                너는 답변 정확도(AA) 평가자다. 질문에 대해 예상 답변과 실제 답변이 핵심 정보에서 일치하는지 판정한다.
                표현, 어순, 말투가 달라도 핵심 정보(이름, 부서, 건수, ID, 날짜, 수치)가 맞으면 Pass 다.
                실제 답변에 틀린 값이 있거나 핵심 정보가 빠졌으면 Fail 이다.
                JSON 하나로만 출력한다: {"pass":true,"reason":"..."}
                """;
        String user = """
                [질문]
                %s

                [예상 답변]
                %s

                [실제 답변]
                %s
                """.formatted(question, expectedAnswer, actualAnswer);
        return new LlmPrompt(system, user, 0.0, 300);
    }

    public LlmPrompt judgeFaithfulness(String question, String resultContext, String answer) {
        String system = """
                너는 근거 충실성(FN) 평가자다. 최종 답변이 실제 SQL 조회 결과에 근거하는지 판정한다.
                답변의 모든 사실(이름, 부서, 연락처, 건수, 날짜, 수치)이 조회 결과에 있으면 Pass.
                조회 결과에 없는 정보가 하나라도 포함되면(추측, 외부 지식, 꾸며낸 값) Fail 이다.
                결과가 비어 있는데 값을 만들어 냈다면 Fail 이다.
                JSON 하나로만 출력한다: {"pass":true,"reason":"..."}
                """;
        String user = """
                [질문]
                %s

                [SQL 조회 결과]
                %s

                [최종 답변]
                %s
                """.formatted(question, resultContext, answer);
        return new LlmPrompt(system, user, 0.0, 300);
    }

    public JudgeVerdict parseVerdict(String raw) {
        JsonNode node = JsonSupport.parseObject(raw);
        if (node == null) {
            return null;
        }
        boolean pass = JsonSupport.bool(node, "pass", false);
        String reason = JsonSupport.text(node, "reason");
        return new JudgeVerdict(pass, reason == null ? "" : reason);
    }

    // ------------------------------------------------------------------
    // 공통 렌더링
    // ------------------------------------------------------------------

    public String resultContext(List<LookupResult> results, int maxRows) {
        if (results == null || results.isEmpty()) {
            return "(조회 결과 없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (LookupResult r : results) {
            sb.append("- task=").append(r.taskId()).append(" / purpose=").append(r.purpose())
                    .append(" / origin=").append(r.origin());
            if (r.failed()) {
                sb.append(" / FAILED: ").append(r.errorMessage()).append('\n');
                continue;
            }
            sb.append(" / rows=").append(r.result().rowCount()).append('\n');
            sb.append("  columns=").append(String.join(", ", r.result().columns())).append('\n');
            int limit = Math.min(maxRows, r.result().rows().size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> row = r.result().rows().get(i);
                sb.append("  row").append(i + 1).append('=').append(JsonSupport.write(row)).append('\n');
            }
            if (r.result().rows().size() > limit) {
                sb.append("  ... ").append(r.result().rows().size() - limit).append(" rows more\n");
            }
        }
        return sb.toString();
    }

    private String historyBlock(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : history) {
            sb.append(turn.role()).append(": ").append(JsonSupport.truncate(turn.content(), 400)).append('\n');
        }
        return sb.toString();
    }

    private String candidateBlock(List<CacheCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "(후보 없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (CacheCandidate c : candidates) {
            sb.append("- id=").append(c.id())
                    .append(" score=").append(String.format("%.3f", c.score()))
                    .append(" (").append(c.scoreType()).append(")\n")
                    .append("  question: ").append(c.question()).append('\n')
                    .append("  sql: ").append(c.sql()).append('\n');
            if (!c.defaultParams().isEmpty()) {
                sb.append("  params: ").append(JsonSupport.write(c.defaultParams())).append('\n');
            }
            sb.append("  tables: ").append(String.join(", ", c.tables())).append('\n');
        }
        return sb.toString();
    }

    private String taskBlock(List<LookupTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (LookupTask t : tasks) {
            sb.append("- ").append(t.id()).append(": ").append(t.purpose())
                    .append(" intent=").append(t.intent())
                    .append(" filters=").append(t.filters()).append('\n');
        }
        return sb.toString();
    }

    private Intent parseIntent(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Intent.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (!n.isNull() && !n.asText().isBlank()) {
                    values.add(n.asText());
                }
            });
        }
        return values;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    values.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return JsonSupport.mapper().convertValue(node, Map.class);
    }
}
