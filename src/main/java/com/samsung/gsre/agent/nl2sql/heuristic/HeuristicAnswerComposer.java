package com.samsung.gsre.agent.nl2sql.heuristic;

import com.samsung.gsre.agent.model.AnswerOutcome;
import com.samsung.gsre.agent.model.LookupResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 최종 답변 생성기 (오프라인 모드 · LLM 응답 파싱 실패 폴백).
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>답변은 "누가/무엇을/어디서" 순으로, 조회 결과 값만 사용한다.</li>
 *   <li>결과가 0건이면 추측하지 않고 "조회 결과가 없습니다"로 끝낸다.</li>
 *   <li>일부 조회가 실패하면 성공한 결과만 답하고, 실패 사실을 함께 밝힌다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>실행 결과를 성공/실패로 분리한다.</li>
 *   <li>결과 컬럼 모양으로 조회 유형(담당자/건수/부서/서비스맵/이력/별칭)을 판별한다.</li>
 *   <li>유형별 문장 템플릿에 값을 채워 답변을 만든다.</li>
 * </ol>
 */
@Component
public class HeuristicAnswerComposer {

    // ------------------------------------------------------------------
    // 하단: 결과 스펙 (AnswerOutcome)
    // ------------------------------------------------------------------

    public AnswerOutcome compose(String question, List<LookupResult> results) {
        if (results == null || results.isEmpty()) {
            return new AnswerOutcome(false, "조회를 수행하지 못했습니다. 질의를 다시 확인해 주세요.",
                    List.of("조회 실행 없음"), List.of());
        }

        List<LookupResult> succeeded = results.stream().filter(r -> !r.failed()).toList();
        List<LookupResult> failed = results.stream().filter(LookupResult::failed).toList();

        StringBuilder answer = new StringBuilder();
        for (LookupResult result : succeeded) {
            String sentence = format(result);
            if (!sentence.isBlank()) {
                if (answer.length() > 0) {
                    answer.append('\n');
                }
                answer.append(sentence);
            }
        }
        if (succeeded.isEmpty()) {
            answer.append("조회에 실패했습니다. 원인: ")
                    .append(failed.isEmpty() ? "알 수 없음" : failed.get(0).errorMessage());
        } else if (!failed.isEmpty()) {
            answer.append("\n일부 조회는 실패했습니다: ")
                    .append(failed.get(0).purpose()).append(" (").append(failed.get(0).errorMessage()).append(')');
        }

        List<String> missing = new ArrayList<>();
        failed.forEach(f -> missing.add(f.purpose() + " 조회 실패"));
        return new AnswerOutcome(failed.isEmpty(), answer.toString(), missing, List.of());
    }

    // ------------------------------------------------------------------
    // 중단: 결과 유형 판별 + 문장 조립
    // ------------------------------------------------------------------

    private String format(LookupResult result) {
        List<Map<String, Object>> rows = result.result().rows();
        List<String> columns = result.result().columns();
        if (rows.isEmpty()) {
            return result.purpose() + " 결과가 없습니다.";
        }
        if (columns.contains("user_count")) {
            return formatCount(rows);
        }
        if (columns.contains("user_name")) {
            return formatContact(rows);
        }
        if (columns.contains("dept_head_name")) {
            return formatDept(rows);
        }
        if (columns.contains("alias_name")) {
            return formatAlias(rows);
        }
        if (columns.contains("issue_key")) {
            return formatWorklog(rows);
        }
        if (columns.contains("service_name") && columns.contains("domain_name")) {
            return formatService(rows);
        }
        return formatGeneric(result.purpose(), rows);
    }

    private String formatCount(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String appId = text(row, "app_id");
            String appName = text(row, "app_name");
            if (appId.isBlank()) {
                sb.append("해당 APP을(를) 사용하는 사용자는 ");
            } else {
                sb.append(appId).append(' ').append(appName).append("을(를) 사용하는 사용자는 ");
            }
            sb.append(number(row.get("user_count"))).append("명입니다.");
        }
        return sb.toString();
    }

    private String formatContact(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text(row, "app_id")).append(' ').append(text(row, "app_name"))
                    .append("의 담당자는 ").append(text(row, "user_name"));
            String dept = text(row, "dept_name");
            if (!dept.isBlank()) {
                sb.append('(').append(dept).append(')');
            }
            sb.append("입니다.");
            List<String> contacts = new ArrayList<>();
            if (!text(row, "email").isBlank()) {
                contacts.add("이메일 " + text(row, "email"));
            }
            if (!text(row, "phone").isBlank()) {
                contacts.add("연락처 " + text(row, "phone"));
            }
            if (!contacts.isEmpty()) {
                sb.append(' ').append(String.join(", ", contacts)).append('.');
            }
        }
        return sb.toString();
    }

    private String formatDept(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text(row, "dept_name")).append("의 부서장은 ")
                    .append(text(row, "dept_head_name")).append("입니다.");
        }
        return sb.toString();
    }

    private String formatAlias(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('"').append(text(row, "alias_name")).append("\" 별칭은 ")
                    .append(text(row, "app_id")).append('(').append(text(row, "app_name")).append(")입니다.");
        }
        return sb.toString();
    }

    private String formatWorklog(List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(text(first, "app_id")).append(' ').append(text(first, "app_name"))
                .append(" 관련 Jira 작업이력은 ").append(rows.size()).append("건입니다.");
        for (Map<String, Object> row : rows) {
            sb.append('\n').append(text(row, "issue_key")).append('(')
                    .append(text(row, "worklog_date")).append(", ")
                    .append(number(row.get("worklog_hours"))).append("시간, ")
                    .append(text(row, "status")).append(", 담당 ")
                    .append(text(row, "assignee_name")).append(')');
        }
        return sb.toString();
    }

    private String formatService(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text(row, "app_id")).append(' ').append(text(row, "app_name"))
                    .append("의 서비스는 ").append(text(row, "service_id")).append(' ')
                    .append(text(row, "service_name"));
            String domain = text(row, "domain_name");
            if (!domain.isBlank()) {
                sb.append("(도메인 ").append(domain).append(')');
            }
            sb.append("입니다.");
        }
        return sb.toString();
    }

    private String formatGeneric(String purpose, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(purpose).append(" 결과 ").append(rows.size()).append("건입니다.");
        int limit = Math.min(rows.size(), 5);
        for (int i = 0; i < limit; i++) {
            sb.append('\n').append(JsonRow.of(rows.get(i)));
        }
        return sb.toString();
    }

    private String text(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? "" : String.valueOf(value);
    }

    private String number(Object value) {
        if (value == null) {
            return "0";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /** 행 하나를 "컬럼=값, ..." 로 표현하는 내부 뷰. */
    private record JsonRow() {
        static String of(Map<String, Object> row) {
            StringBuilder sb = new StringBuilder();
            row.forEach((k, v) -> {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(k).append('=').append(v);
            });
            return sb.toString();
        }
    }
}
