package com.samsung.gsre.agent.mcp;

/**
 * Reshaping 룰이 잘못됐거나 필수 필드를 만들 수 없을 때.
 * 룰은 운영자가 테이블에서 직접 고치는 값이므로, 메시지는 "어느 필드의 어느 경로" 인지까지 알려준다.
 * IllegalArgumentException 을 상속해 API 계층에서 400 으로 응답된다.
 */
public class ReshapingException extends IllegalArgumentException {

    public ReshapingException(String message) {
        super(message);
    }

    public ReshapingException(String message, Throwable cause) {
        super(message, cause);
    }
}
