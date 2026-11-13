package com.samsung.gsre.agent.eval;

import com.samsung.gsre.agent.model.JudgeVerdict;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 LLM-as-a-Judge 대체 구현.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>AA(답변 정확도): 예상 답변의 핵심 토큰 70% 이상이 실제 답변에 있어야 Pass. 숫자·식별자는 전부 일치해야 한다.</li>
 *   <li>FN(근거 충실성): 실제 답변의 모든 숫자·식별자가 조회 결과에 존재해야 Pass.</li>
 *   <li>표현·어순 차이는 Fail 사유가 아니다.</li>
 * </ul>
 *
 * <h2>가공 흐름</h2>
 * <ol>
 *   <li>문장을 소문자로 정규화하고 공백·구두점을 제거한다.</li>
 *   <li>핵심 토큰(2자 이상 한글/영문, 숫자)을 추출한다.</li>
 *   <li>포함 관계와 토큰 커버리지로 Pass/Fail 과 사유를 만든다.</li>
 * </ol>
 */
@Component
public class HeuristicJudge {

    // ------------------------------------------------------------------
    // 상단: 판정 기준정보
    // ------------------------------------------------------------------

    private static final double ANSWER_TOKEN_COVERAGE = 0.70;
    private static final Pattern TOKEN = Pattern.compile("[가-힣a-zA-Z]{2,}|\\d+(?:\\.\\d+)?");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final String EMPTY_RESULT_MARK = "(조회 결과 없음)";

    // ------------------------------------------------------------------
    // 중단: 판정 로직
    // ------------------------------------------------------------------

    public JudgeVerdict answer(String question, String expectedAnswer, String actualAnswer) {
        String expected = normalize(expectedAnswer);
        String actual = normalize(actualAnswer);
        if (expected.isEmpty()) {
            return JudgeVerdict.fail("예상 답변이 등록되지 않아 AA 를 판정할 수 없습니다.");
        }
        if (actual.isEmpty()) {
            return JudgeVerdict.fail("실제 답변이 비어 있습니다.");
        }
        if (actual.contains(expected) || expected.contains(actual)) {
            return JudgeVerdict.pass("예상 답변의 핵심 내용이 실제 답변에 포함되어 있습니다.");
        }

        Set<String> expectedTokens = tokens(expectedAnswer);
        Set<String> actualTokens = tokens(actualAnswer);
        List<String> missingNumbers = new ArrayList<>();
        for (String token : expectedTokens) {
            if (isNumber(token) && !actualTokens.contains(token)) {
                missingNumbers.add(token);
            }
        }
        int matched = 0;
        for (String token : expectedTokens) {
            if (actualTokens.contains(token) || actual.contains(token)) {
                matched++;
            }
        }
        double coverage = expectedTokens.isEmpty() ? 1.0 : (double) matched / expectedTokens.size();

        if (!missingNumbers.isEmpty()) {
            return JudgeVerdict.fail("예상 답변의 수치/식별자 " + missingNumbers + " 가 실제 답변에 없습니다.");
        }
        if (coverage >= ANSWER_TOKEN_COVERAGE) {
            return JudgeVerdict.pass(String.format(
                    "핵심 정보가 일치합니다(토큰 커버리지 %.0f%%).", coverage * 100));
        }
        return JudgeVerdict.fail(String.format(
                "핵심 정보가 부족합니다(토큰 커버리지 %.0f%%).", coverage * 100));
    }

    public JudgeVerdict faithfulness(String resultContext, String answer) {
        String normalizedAnswer = normalize(answer);
        String normalizedContext = normalize(resultContext);
        if (normalizedAnswer.isEmpty()) {
            return JudgeVerdict.fail("답변이 비어 있습니다.");
        }
        boolean emptyResult = resultContext == null || resultContext.isBlank()
                || resultContext.contains(EMPTY_RESULT_MARK);
        if (emptyResult) {
            return normalizedAnswer.contains(normalize("없습니다"))
                    ? JudgeVerdict.pass("조회 결과가 없음을 그대로 밝혔습니다.")
                    : JudgeVerdict.fail("조회 결과가 없는데 답변에 값이 포함되어 있습니다.");
        }

        List<String> unsupported = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(answer);
        while (matcher.find()) {
            String number = matcher.group();
            if (!normalizedContext.contains(normalize(number))) {
                unsupported.add(number);
            }
        }
        if (!unsupported.isEmpty()) {
            return JudgeVerdict.fail("조회 결과에 없는 값이 답변에 포함되어 있습니다: " + unsupported);
        }
        return JudgeVerdict.pass("답변의 수치·식별자가 모두 조회 결과에 근거합니다.");
    }

    // ------------------------------------------------------------------
    // 하단: 문자열 유틸 (결과 가공)
    // ------------------------------------------------------------------

    private Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.KOREAN));
        }
        return result;
    }

    private boolean isNumber(String token) {
        return token.matches("\\d+(?:\\.\\d+)?");
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.KOREAN).replaceAll("[\\s\\p{Punct}·“”‘’]", "");
    }
}
