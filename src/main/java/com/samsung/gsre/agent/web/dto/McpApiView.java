package com.samsung.gsre.agent.web.dto;

import java.time.Instant;

/** MCP API 등록 현황(관리 화면용). 룰 원문을 그대로 보여줘야 운영자가 고칠 수 있다. */
public record McpApiView(
        String apiId,
        String apiName,
        String toolName,
        String direction,
        String transportType,
        String endpointUrl,
        String authType,
        int timeoutMs,
        boolean active,
        String description,
        String requestSchema,
        String requestReshapingRule,
        String responseReshapingRule,
        String sourceQuery,
        Instant createdAt) {
}
