package com.samsung.gsre.agent.mcp.transport;

import java.util.Locale;

/**
 * REST API 로 내보낼 때 쓸 HTTP 메서드. ({@code svcm_tb_mcp_api_master.http_method})
 *
 * <p>MCP tools/call 은 언제나 POST 지만, REST API 는 조회(GET)·삭제(DELETE)·수정(PUT/PATCH)을
 * 같은 테이블 설정만으로 다뤄야 하므로 메서드도 데이터가 된다.</p>
 *
 * <p>메서드에 따라 전송 방식이 달라진다. GET/DELETE 는 본문을 보내지 않고 남은 파라미터를
 * Query String 으로 붙이고, 나머지는 JSON 본문을 보낸다.</p>
 */
public enum McpHttpMethod {

    GET,
    POST,
    PUT,
    PATCH,
    DELETE;

    /** 본문(JSON body)을 보내는 메서드인지. GET/DELETE 는 Query String 만 쓴다. */
    public boolean hasBody() {
        return this == POST || this == PUT || this == PATCH;
    }

    /** http_method 값이 비어 있으면 POST 로 본다(기존 등록 행 호환). */
    public static McpHttpMethod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return POST;
        }
        try {
            return McpHttpMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 http_method 입니다: " + raw
                    + " (GET / POST / PUT / PATCH / DELETE)");
        }
    }
}
