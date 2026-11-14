package com.samsung.gsre.agent.security.net;

/**
 * 외부 호출 대상이 정책상 허용되지 않을 때 던지는 예외.
 *
 * <p>등록 API 에서는 400({@code OUTBOUND_TARGET_BLOCKED}), 실행 중에는 해당 호출 FAILED 로 남는다.
 * 메시지에는 차단 사유만 담고, 시크릿이 될 수 있는 전체 URL 은 담지 않는다.</p>
 */
public class OutboundTargetBlockedException extends IllegalArgumentException {

    public OutboundTargetBlockedException(String message) {
        super(message);
    }
}
