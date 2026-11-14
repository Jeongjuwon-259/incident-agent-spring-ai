package com.samsung.gsre.agent.common.validation;

/**
 * 입력 검증 실패를 나타내는 공통 예외.
 *
 * <p>{@link IllegalArgumentException} 을 상속하므로 기존 웹 예외 처리기(400 BAD_REQUEST)가
 * 그대로 동작한다. 도메인별 예외(404 {@code McpApiNotFoundException}, SSRF
 * {@code OutboundTargetBlockedException})와 구분해 "값이 잘못됐다"는 의미만 담는다.</p>
 */
public class ValidationException extends IllegalArgumentException {

    public ValidationException(String message) {
        super(message);
    }
}
