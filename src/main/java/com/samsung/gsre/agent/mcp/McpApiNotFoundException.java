package com.samsung.gsre.agent.mcp;

/** 등록되지 않았거나 비활성(active_yn='N') 인 MCP API 를 실행하려 할 때. API 에서는 404. */
public class McpApiNotFoundException extends RuntimeException {

    public McpApiNotFoundException(String apiId) {
        super("등록된 MCP API 를 찾지 못했습니다: " + apiId + " (svcm_tb_mcp_api_master 확인)");
    }
}
