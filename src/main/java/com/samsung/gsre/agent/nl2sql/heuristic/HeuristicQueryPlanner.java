package com.samsung.gsre.agent.nl2sql.heuristic;

import com.samsung.gsre.agent.model.CacheCandidate;
import com.samsung.gsre.agent.model.ConversationTurn;
import com.samsung.gsre.agent.model.Intent;
import com.samsung.gsre.agent.model.LookupTask;
import com.samsung.gsre.agent.model.QueryPlan;
import com.samsung.gsre.agent.model.ReuseDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 질의 이해기.
 *
 * <p>두 곳에서 쓰인다.
 * <ul>
 *   <li>{@code agent.llm.mode=offline} 인 경우 파이프라인 전체를 모델 없이 돌리기 위한 기본 구현</li>
 *   <li>로컬 LLM 응답이 JSON 으로 파싱되지 않을 때의 폴백</li>
 * </ul>
 * 한국어 데모 질의(담당자, 사용자 수, 부서장, 서비스맵, Jira 이력, 별칭)를 결정적으로 해석한다.</p>
 */
@Component
public class HeuristicQueryPlanner {

    private static final Pattern APP_ID = Pattern.compile("(?i)APP\\s*[-_ ]?\\s*(\\d{3,5})");
    private static final Pattern DEPT_NAME = Pattern.compile("([가-힣A-Za-z0-9]+(?:팀|부|실|센터|그룹|본부))");
    private static final Pattern ALIAS_HINT = Pattern.compile("[\"'“”]?([가-힣A-Za-z0-9 ]{2,20})[\"'“”]?\\s*(?:이라는|라는|이라고|별칭)");

    private static final List<String> NOISE = List.of(
            "알려줘", "알려", "보여줘", "보여", "조회해줘", "조회", "궁금해", "누구야", "누구인가", "누구",
            "뭐야", "무엇인가", "인가", "인지", "해줘", "좀", "그리고", "현재", "최근", "대한", "관련",
            "있어", "있나", "있습니까", "뭐가", "어떤", "몇", "명", "건", "개");

    public QueryPlan plan(String question, List<ConversationTurn> history) {
        String normalized = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.KOREAN);

        List<String> appIds = extractAppIds(normalized);
        boolean followUp = appIds.isEmpty() && inheritsAppId(history);
        if (followUp) {
            appIds = appIdsFromHistory(history);
        }

        Map<String, String> filters = new LinkedHashMap<>();
        if (!appIds.isEmpty()) {
            filters.put("appId", appIds.get(0));
        }
        extractDeptName(normalized).ifPresent(d -> filters.put("deptName", d));
        extractAlias(normalized).ifPresent(a -> filters.put("aliasName", a));
        if (!filters.containsKey("appId")) {
            residualAppName(normalized, lower).ifPresent(n -> filters.put("appName", n));
        }

        List<LookupTask> tasks = new ArrayList<>();
        Intent primary = detectIntent(lower);
        if (asksContact(lower) && asksUserCount(lower)) {
            tasks.add(task("T1", "담당자 조회", Intent.CONTACT, filters));
            tasks.add(task("T2", "사용자 수 집계", Intent.USER_ACCESS, filters));
        } else {
            tasks.add(task("T1", purposeOf(primary, filters), primary, filters));
        }

        List<String> keywords = keywords(normalized);
        return new QueryPlan(normalized, primary, appIds, keywords, tasks, tasks.size() > 1, followUp,
                "규칙 기반 해석: intent=" + primary + ", appIds=" + appIds + ", filters=" + filters);
    }

    /** 유사도만으로 재사용을 확정하지 않는다: 대상 APP/부서 조건이 같을 때만 재사용한다. */
    public ReuseDecision decideReuse(LookupTask task, List<CacheCandidate> candidates) {
        Map<String, Object> params = new LinkedHashMap<>(task.filters());
        for (CacheCandidate candidate : candidates) {
            String question = candidate.question() == null ? "" : candidate.question().toLowerCase(Locale.KOREAN);
            boolean appMatch = task.appId() == null || question.contains(task.appId().toLowerCase(Locale.KOREAN))
                    || candidate.defaultParams().containsKey("appId");
            boolean purposeMatch = matchesPurpose(task.intent(), candidate.sql());
            if (candidate.score() >= 0.55 && appMatch && purposeMatch) {
                Map<String, Object> merged = new LinkedHashMap<>(candidate.defaultParams());
                merged.putAll(params);
                return ReuseDecision.accept(candidate.id(),
                        "대상과 조회 목적이 일치하는 검증 SQL(유사도 " + String.format("%.2f", candidate.score()) + ")을 재사용합니다.",
                        merged);
            }
        }
        return ReuseDecision.reject(candidates.isEmpty()
                ? "검증 SQL 후보가 없습니다."
                : "후보 SQL 의 조회 대상·조건이 현재 질문과 일치하지 않습니다.");
    }

    private boolean matchesPurpose(Intent intent, String sql) {
        String s = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        return switch (intent) {
            case CONTACT -> s.contains("user_info_master") && s.contains("owner");
            case USER_ACCESS, COUNT -> s.contains("count(") && s.contains("app_user_map");
            case DEPT -> s.contains("dept_info_master") && s.contains("dept_head_name");
            case SERVICE_MAP -> s.contains("service_map_master");
            case JIRA_WORKLOG -> s.contains("jira_dri_worklog");
            case ALIAS -> s.contains("app_alias");
            case APP_INFO, GENERAL -> s.contains("app_master");
        };
    }

    private LookupTask task(String id, String purpose, Intent intent, Map<String, String> filters) {
        return new LookupTask(id, purpose, intent, new ArrayList<>(tablesOf(intent)), filters);
    }

    private List<String> tablesOf(Intent intent) {
        return switch (intent) {
            case CONTACT -> List.of("svcm_tb_app_master", "svcm_tb_app_user_map", "svcm_tb_user_info_master",
                    "svcm_tb_dept_info_master");
            case USER_ACCESS, COUNT -> List.of("svcm_tb_app_user_map", "svcm_tb_app_master");
            case DEPT -> List.of("svcm_tb_dept_info_master");
            case SERVICE_MAP -> List.of("svcm_tb_app_master", "svcm_tb_service_map_master");
            case JIRA_WORKLOG -> List.of("svcm_vw_jira_dri_worklog");
            case ALIAS -> List.of("svcm_tb_app_alias", "svcm_tb_app_master");
            case APP_INFO, GENERAL -> List.of("svcm_tb_app_master", "svcm_tb_dept_info_master",
                    "svcm_tb_service_map_master");
        };
    }

    private String purposeOf(Intent intent, Map<String, String> filters) {
        String target = filters.getOrDefault("appId",
                filters.getOrDefault("aliasName", filters.getOrDefault("deptName",
                        filters.getOrDefault("appName", "대상"))));
        return switch (intent) {
            case CONTACT -> target + " 담당자 조회";
            case USER_ACCESS -> target + " 사용자 수 집계";
            case COUNT -> target + " 건수 집계";
            case DEPT -> target + " 부서/부서장 조회";
            case SERVICE_MAP -> target + " 서비스맵 조회";
            case JIRA_WORKLOG -> target + " Jira 작업이력 조회";
            case ALIAS -> target + " APP 별칭 조회";
            case APP_INFO, GENERAL -> target + " APP 기본정보 조회";
        };
    }

    private Intent detectIntent(String lower) {
        if (asksContact(lower)) {
            return Intent.CONTACT;
        }
        if (lower.contains("부서장") || lower.contains("팀장") || lower.contains("부서")) {
            return Intent.DEPT;
        }
        if (lower.contains("jira") || lower.contains("지라") || lower.contains("작업이력")
                || lower.contains("작업 이력") || lower.contains("장애이력") || lower.contains("이슈")) {
            return Intent.JIRA_WORKLOG;
        }
        if (lower.contains("별칭") || lower.contains("약칭") || lower.contains("alias")) {
            return Intent.ALIAS;
        }
        if (lower.contains("서비스맵") || lower.contains("서비스 맵") || lower.contains("도메인")) {
            return Intent.SERVICE_MAP;
        }
        if (asksUserCount(lower)) {
            return Intent.USER_ACCESS;
        }
        if (lower.contains("검수") || lower.contains("승인")) {
            return Intent.APP_INFO;
        }
        return Intent.APP_INFO;
    }

    private boolean asksContact(String lower) {
        return lower.contains("담당자") || lower.contains("오너") || lower.contains("owner")
                || lower.contains("연락처") || lower.contains("이메일") || lower.contains("메일")
                || lower.contains("전화번호");
    }

    private boolean asksUserCount(String lower) {
        return lower.contains("사용자 수") || lower.contains("사용자수") || lower.contains("몇 명")
                || lower.contains("몇명") || lower.contains("인원") || lower.contains("접근 권한")
                || lower.contains("권한 가진");
    }

    public static List<String> extractAppIds(String text) {
        List<String> ids = new ArrayList<>();
        if (text == null) {
            return ids;
        }
        Matcher matcher = APP_ID.matcher(text);
        while (matcher.find()) {
            String id = "APP-" + matcher.group(1);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Optional<String> extractDeptName(String text) {
        Matcher matcher = DEPT_NAME.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!candidate.equals("부서") && !candidate.endsWith("담당자")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractAlias(String text) {
        Matcher matcher = ALIAS_HINT.matcher(text);
        if (matcher.find()) {
            String alias = matcher.group(1).replaceAll("(이라는|라는|별칭|APP|app)", "").trim();
            if (alias.length() >= 2) {
                return Optional.of(alias);
            }
        }
        return Optional.empty();
    }

    /** APP ID 가 없을 때 APP 명으로 검색할 잔여 키워드를 뽑는다. (예: "서비스맵포털 담당자" -> 서비스맵포털) */
    private Optional<String> residualAppName(String text, String lower) {
        String cleaned = text;
        for (String noise : NOISE) {
            cleaned = cleaned.replace(noise, " ");
        }
        cleaned = cleaned.replaceAll("[?？!.,'\"()\\[\\]]", " ");
        String best = null;
        for (String token : cleaned.split("\\s+")) {
            String t = token.trim();
            if (t.length() < 2 || NOISE.contains(t) || t.matches("[0-9]+") || t.startsWith("APP")) {
                continue;
            }
            if (lower.contains("별칭") && t.equals("별칭")) {
                continue;
            }
            if (t.endsWith("팀") || t.endsWith("부서")) {
                continue;
            }
            if (best == null || t.length() > best.length()) {
                best = t;
            }
        }
        return Optional.ofNullable(best);
    }

    private List<String> keywords(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            String t = token.replaceAll("[?？!.,'\"()\\[\\]]", "").trim();
            if (t.length() >= 2 && !NOISE.contains(t) && !result.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }

    private boolean inheritsAppId(List<ConversationTurn> history) {
        return !appIdsFromHistory(history).isEmpty();
    }

    private List<String> appIdsFromHistory(List<ConversationTurn> history) {
        if (history == null) {
            return List.of();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            List<String> ids = extractAppIds(history.get(i).content());
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return List.of();
    }
}
