package com.samsung.gsre.agent.gateway;

/** SQL 실행 실패. 메시지는 Self-Repair 프롬프트의 오류 피드백으로 그대로 사용된다. */
public class SqlExecutionException extends RuntimeException {

    public SqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public SqlExecutionException(String message) {
        super(message);
    }
}
