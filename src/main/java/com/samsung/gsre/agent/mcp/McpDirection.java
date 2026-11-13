package com.samsung.gsre.agent.mcp;

import java.util.Locale;

/**
 * MCP 연동 방향. 이 값 하나로 "원천 로우데이터를 읽는가" 가 결정된다.
 *
 * <ul>
 *   <li>{@link #OUTBOUND} — 외부 MCP 도구를 호출하고, 돌아온 거대한 Raw 응답을 룰로 정제한다.</li>
 *   <li>{@link #INBOUND} — 내부 DB 로우데이터를 MCP 스펙으로 재가공해 외부로 발송한다.</li>
 *   <li>{@link #BIDIRECTIONAL} — 위 두 방향을 한 번의 실행에서 모두 수행한다.</li>
 * </ul>
 */
public enum McpDirection {

    OUTBOUND,
    INBOUND,
    BIDIRECTIONAL;

    /** INBOUND/BIDIRECTIONAL 이면 master.source_query 로 내부 로우데이터를 먼저 읽는다. */
    public boolean readsSourceRows() {
        return this == INBOUND || this == BIDIRECTIONAL;
    }

    /** 모든 방향이 MCP 엔드포인트로 tools/call 을 보낸다. (수신 응답은 룰이 있으면 정제) */
    public boolean callsEndpoint() {
        return true;
    }

    public static McpDirection from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("direction 은 필수입니다. (OUTBOUND / INBOUND / BIDIRECTIONAL)");
        }
        try {
            return McpDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "지원하지 않는 direction 입니다: " + raw + " (OUTBOUND / INBOUND / BIDIRECTIONAL)");
        }
    }
}
