package com.samsung.gsre.agent.llm.exception;

/** LLM 호출 계층 예외. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 로컬 LLM 엔드포인트에 연결하지 못한 경우(모델 미기동, 주소 오류). */
    public static class LlmUnavailableException extends LlmException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 호출 예산(max-llm-calls) 초과. */
    public static class LlmBudgetExceededException extends LlmException {
        public LlmBudgetExceededException(String message) {
            super(message);
        }
    }
}
