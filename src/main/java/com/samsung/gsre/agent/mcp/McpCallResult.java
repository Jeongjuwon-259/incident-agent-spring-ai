package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 외부 MCP 도구 호출 1회의 결과.
 *
 * @param rawBody     HTTP 응답 본문 그대로 (감사 로그의 raw_payload_out)
 * @param payload     JSON-RPC/SSE 포장을 벗긴 도구 결과 (Reshaping 입력)
 * @param statusCode  HTTP 상태 코드 (픽스처 폴백이면 0)
 * @param requestBody 실제로 보낸 요청 본문 (시크릿은 포함되지 않는다)
 * @param fallback    실제 호출 실패로 픽스처를 썼는지
 */
public record McpCallResult(
        String rawBody,
        JsonNode payload,
        int statusCode,
        long durationMs,
        String requestBody,
        boolean fallback,
        String errorMessage) {

    public boolean httpError() {
        return !fallback && statusCode >= 400;
    }
}
