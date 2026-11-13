package com.samsung.gsre.agent.web.dto;

import java.util.Map;

/**
 * MCP 실행 요청.
 *
 * @param params  도구에 넘길 파라미터. INBOUND/BIDIRECTIONAL 이면 source_query 의 named parameter 로도 쓰인다.
 * @param runId   에이전트 실행 ID (있으면 감사 로그를 질의 실행과 연결)
 * @param requestedBy 요청자 (감사 목적)
 */
public record McpExecuteRequest(Map<String, Object> params, String runId, String requestedBy) {

    public Map<String, Object> paramsOrEmpty() {
        return params == null ? Map.of() : params;
    }
}
