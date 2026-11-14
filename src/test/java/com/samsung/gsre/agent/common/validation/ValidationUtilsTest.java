package com.samsung.gsre.agent.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 공통 검증 유틸: 업무 소스의 널/공백/존재/형식 if 문을 대체하는 계약. */
class ValidationUtilsTest {

    @Test
    @DisplayName("필수 텍스트는 null/공백이면 ValidationException 으로 변환된다")
    void requireText() {
        assertThat(ValidationUtils.requireText("APP-1001", "appId")).isEqualTo("APP-1001");
        assertThatThrownBy(() -> ValidationUtils.requireText(null, "appId"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("appId");
        assertThatThrownBy(() -> ValidationUtils.requireText("  ", "appId", "appId 는 필수입니다."))
                .isInstanceOf(ValidationException.class)
                .hasMessage("appId 는 필수입니다.");
    }

    @Test
    @DisplayName("엔터티/Optional 존재 확인은 메시지를 그대로 전달한다")
    void requirePresentAndFound() {
        assertThat(ValidationUtils.requirePresent("x", "없음")).isEqualTo("x");
        assertThatThrownBy(() -> ValidationUtils.requirePresent(null, "MCP API 정의가 없습니다."))
                .hasMessage("MCP API 정의가 없습니다.");

        assertThat(ValidationUtils.requireFound(Optional.of(7L), "없음")).isEqualTo(7L);
        assertThatThrownBy(() -> ValidationUtils.requireFound(Optional.empty(), "찾을 수 없습니다: 1"))
                .hasMessage("찾을 수 없습니다: 1");
    }

    @Test
    @DisplayName("허용 목록·정규식·범위 검증은 조건을 한 줄로 표현한다")
    void requireAllowedPatternAndRange() {
        assertThat(ValidationUtils.requireOneOf("REST", Set.of("REST", "MCP"), "미지원"))
                .isEqualTo("REST");
        assertThatThrownBy(() -> ValidationUtils.requireOneOf("GRPC", Set.of("REST"), "미지원"))
                .hasMessage("미지원");

        assertThat(ValidationUtils.requirePattern("MCP_TOKEN", Pattern.compile("^[A-Z_]+$"), "형식"))
                .isEqualTo("MCP_TOKEN");
        assertThatThrownBy(() -> ValidationUtils.requirePattern("bad name", Pattern.compile("^[A-Z_]+$"), "형식"))
                .hasMessage("형식");

        assertThat(ValidationUtils.requireRange(5, 1, 10, "범위")).isEqualTo(5);
        assertThatThrownBy(() -> ValidationUtils.requireRange(0, 1, 10, "범위")).hasMessage("범위");
        assertThatThrownBy(() -> ValidationUtils.requirePositive(0, "양수")).hasMessage("양수");
        assertThatThrownBy(() -> ValidationUtils.requireTrue(false, "조건")).hasMessage("조건");
    }

    @Test
    @DisplayName("기본값 대체는 검증 실패가 아니라 정규화로 동작한다")
    void defaults() {
        assertThat(ValidationUtils.orDefault(" ", "WEB")).isEqualTo("WEB");
        assertThat(ValidationUtils.orDefault("MESSENGER", "WEB")).isEqualTo("MESSENGER");
        assertThat(ValidationUtils.orDefault((String) null, "anonymous")).isEqualTo("anonymous");
        assertThat(ValidationUtils.firstNonBlank(null, " ", "payload")).isEqualTo("payload");
        assertThat(ValidationUtils.firstNonBlank(null, " ")).isNull();
    }
}
