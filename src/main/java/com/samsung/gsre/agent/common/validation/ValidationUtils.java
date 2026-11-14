package com.samsung.gsre.agent.common.validation;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 반복되는 널/공백/존재/범위/형식 검증을 한 곳에 모은 공통 유틸.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <p>업무 소스에는 검증 `if` 문을 흩어 놓지 않고, 이 유틸의 이름 있는 메서드로 의도를 드러낸다.
 * 실패는 {@link ValidationException}(= IllegalArgumentException) 으로 변환되어 400 응답이 된다.</p>
 *
 * <h2>중단 (PROCESS) — 검증</h2>
 * <p>값이 통과하면 <b>원래 값</b>을 그대로 돌려주어 호출부에서 바로 대입·사용할 수 있게 한다.
 * (예: {@code String apiId = ValidationUtils.requireText(api.apiId(), "apiId");})</p>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>검증된 값 또는 기본값. 실패 시 {@link ValidationException}.</p>
 *
 * <p><b>사용 원칙</b>: 한 줄짜리 널 체크라도 의미가 있는 검증이면 이 유틸을 쓴다.
 * 단, "결과가 없으면 실패" 처럼 도메인 의미가 강한 분기는 도메인 예외를 그대로 쓴다
 * (예: 등록되지 않은 MCP API → 404).</p>
 */
public final class ValidationUtils {

    private ValidationUtils() {
    }

    // ==================================================================
    // 상단 (INPUT) — 값 판정
    // ==================================================================

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean hasText(String value) {
        return !isBlank(value);
    }

    public static boolean isNull(Object value) {
        return value == null;
    }

    // ==================================================================
    // 중단 (PROCESS) — 필수값·존재·형식 검증
    // ==================================================================

    /** null/공백이면 {@code "<field> 값이 필요합니다."} 로 실패한다. */
    public static String requireText(String value, String field) {
        return requireText(value, field, field + " 값이 필요합니다.");
    }

    public static String requireText(String value, String field, String message) {
        if (isBlank(value)) {
            throw new ValidationException(message);
        }
        return value;
    }

    /** null 이면 메시지와 함께 실패한다. (엔터티/객체 존재 확인) */
    public static <T> T requirePresent(T value, String message) {
        if (value == null) {
            throw new ValidationException(message);
        }
        return value;
    }

    /** Optional 이 비어 있으면 메시지와 함께 실패한다. (조회 결과 존재 확인) */
    public static <T> T requireFound(Optional<T> value, String message) {
        return value.orElseThrow(() -> new ValidationException(message));
    }

    /** 조건이 거짓이면 실패한다. (중복 등록, 정책 위반 등) */
    public static void requireTrue(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException(message);
        }
    }

    /** 허용 목록에 없으면 실패한다. (전송 방식·인증 방식 등) */
    public static String requireOneOf(String value, Set<String> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw new ValidationException(message);
        }
        return value;
    }

    /** 패턴과 맞지 않으면 실패한다. (환경변수 이름·헤더 이름 등) */
    public static String requirePattern(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ValidationException(message);
        }
        return value;
    }

    /** 길이 상한을 넘으면 실패한다. */
    public static String requireMaxLength(String value, int max, String message) {
        if (value != null && value.length() > max) {
            throw new ValidationException(message);
        }
        return value;
    }

    /** 범위를 벗어나면 실패한다. */
    public static int requireRange(int value, int min, int max, String message) {
        if (value < min || value > max) {
            throw new ValidationException(message);
        }
        return value;
    }

    public static int requirePositive(int value, String message) {
        if (value <= 0) {
            throw new ValidationException(message);
        }
        return value;
    }

    public static long requireNonNegative(long value, String message) {
        if (value < 0) {
            throw new ValidationException(message);
        }
        return value;
    }

    // ==================================================================
    // 하단 (OUTPUT) — 기본값 대체 (검증 실패가 아닌 정규화)
    // ==================================================================

    public static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    public static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    /** 앞에서부터 처음 non-blank 값을 고른다. 모두 비면 null. */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
