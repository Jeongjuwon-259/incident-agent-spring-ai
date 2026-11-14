package com.samsung.gsre.agent.common.constant;

import java.util.Set;

/**
 * MCP/REST 연동에서 데이터(테이블 값)로 쓰이는 고정 문자열.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <p>방향·전송 방식·인증 방식·HTTP 헤더 이름·기본 메서드. 게이트웨이·검증기·리포지토리가
 * 같은 문자열을 서로 다르게 적지 않도록 한 곳에 모은다.</p>
 *
 * <h2>중단·하단</h2>
 * <p>값만 제공한다. 새 프로토콜을 추가하면 이 상수와 {@code McpApiMaster.isRestTransport()},
 * 게이트웨이 선택 분기 1줄만 함께 본다.</p>
 */
public final class McpConstants {

    // ---------- direction ----------
    public static final String DIRECTION_OUTBOUND = "OUTBOUND";
    public static final String DIRECTION_INBOUND = "INBOUND";
    public static final String DIRECTION_BIDIRECTIONAL = "BIDIRECTIONAL";

    // ---------- transport_type ----------
    public static final String TRANSPORT_MCP = "MCP";
    public static final String TRANSPORT_HTTP = "HTTP";
    public static final String TRANSPORT_SSE = "SSE";
    public static final String TRANSPORT_REST = "REST";
    public static final String TRANSPORT_REST_API = "REST_API";
    /** MCP Streamable HTTP(tools/call) 로 나가는 전송 방식. */
    public static final Set<String> MCP_TRANSPORTS = Set.of(TRANSPORT_MCP, TRANSPORT_HTTP, TRANSPORT_SSE);
    /** JSON-RPC 봉투 없이 순수 REST 로 나가는 전송 방식. */
    public static final Set<String> REST_TRANSPORTS = Set.of(TRANSPORT_REST, TRANSPORT_REST_API);
    public static final Set<String> TRANSPORT_TYPES = Set.of(
            TRANSPORT_MCP, TRANSPORT_HTTP, TRANSPORT_SSE, TRANSPORT_REST, TRANSPORT_REST_API);
    /** 오류 메시지용 표기(결정적 순서). */
    public static final String TRANSPORT_TYPES_LABEL = "MCP / HTTP / SSE / REST / REST_API";

    // ---------- auth_type ----------
    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_BEARER = "BEARER";
    public static final String AUTH_API_KEY = "API_KEY";
    public static final String AUTH_BASIC = "BASIC";
    public static final Set<String> AUTH_TYPES = Set.of(AUTH_NONE, AUTH_BEARER, AUTH_API_KEY, AUTH_BASIC);
    /** 오류 메시지용 표기(결정적 순서). */
    public static final String AUTH_TYPES_LABEL = "NONE / BEARER / API_KEY / BASIC";
    /** auth_config JSON 의 키. 값이 아니라 환경변수 이름을 담는다. */
    public static final String AUTH_CONFIG_ENV_VAR = "envVar";
    public static final String AUTH_CONFIG_HEADER = "header";

    // ---------- transport mode (agent.mcp.transport-mode) ----------
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_OFFLINE = "OFFLINE";
    public static final String MODE_HTTP = "HTTP";

    // ---------- HTTP 메서드·헤더 ----------
    public static final String HTTP_METHOD_DEFAULT = "POST";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String HEADER_DEFAULT_API_KEY = "X-Api-Key";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_COOKIE = "Cookie";
    public static final String HEADER_SET_COOKIE = "Set-Cookie";
    public static final String AUTH_SCHEME_BEARER = "Bearer ";
    public static final String AUTH_SCHEME_BASIC = "Basic ";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String ACCEPT_JSON_AND_SSE = "application/json, text/event-stream";

    // ---------- 마스터 플래그 ----------
    public static final String ACTIVE_YN = "Y";
    public static final String INACTIVE_YN = "N";
    /** 아직 저장되지 않은 감사 로그 ID. */
    public static final long LOG_ID_NONE = -1L;
    /** 감사 로그 조회 1회 최대 건수. */
    public static final int MAX_LOG_QUERY_LIMIT = 200;

    private McpConstants() {
    }
}
