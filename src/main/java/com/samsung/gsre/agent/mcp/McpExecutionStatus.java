package com.samsung.gsre.agent.mcp;

/** MCP 실행 결과 상태. FALLBACK 은 "데모용 픽스처로 성공" 이라는 뜻이지 실제 외부 반영이 아니다. */
public enum McpExecutionStatus {
    SUCCESS,
    FALLBACK,
    FAILED
}
