package com.samsung.gsre.agent.mcp.exception;

/** 실제 MCP 엔드포인트 호출이 실패했고(폴백 없이) 실행을 계속할 수 없을 때. API 에서는 502 로 응답한다. */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
