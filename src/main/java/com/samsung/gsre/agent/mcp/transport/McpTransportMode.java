package com.samsung.gsre.agent.mcp.transport;

import com.samsung.gsre.agent.common.constant.McpConstants;
import java.util.Locale;

/** MCP 통신 모드. 테스트/데모에서 네트워크 없이 같은 흐름을 재현하기 위한 스위치다. */
public enum McpTransportMode {

    /** 실제 호출을 시도하고, 연결 자체가 실패하면 결정적 픽스처로 대체한다. (기본) */
    AUTO,
    /** 네트워크를 쓰지 않고 픽스처만 사용한다. (데모·단위검증) */
    OFFLINE,
    /** 실제 호출만 허용한다. 실패하면 숨기지 않고 그대로 오류로 남긴다. (운영) */
    HTTP;

    public static McpTransportMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return valueOf(McpConstants.MODE_AUTO);
        }
        try {
            return McpTransportMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 mcp transport-mode 입니다: " + raw
                    + " (" + McpConstants.MODE_AUTO + " / " + McpConstants.MODE_OFFLINE + " / "
                    + McpConstants.MODE_HTTP + ")");
        }
    }
}
