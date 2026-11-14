package com.samsung.gsre.agent.common.constant;

import java.util.List;
import java.util.Set;

/**
 * 보안 경계에서 반복되는 고정값 (마스킹·SSRF 정책).
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>마스킹 자리표시자</li>
 *   <li>시크릿으로 취급하는 키 이름·접미사</li>
 *   <li>설정과 무관하게 항상 차단하는 클라우드 메타데이터 주소</li>
 * </ul>
 *
 * <h2>중단·하단</h2>
 * <p>{@code security.mask.SensitiveDataMasker} 와 {@code security.net.OutboundTargetPolicy} 가
 * 이 값만 참조한다. 새 시크릿 키/차단 대상을 추가할 때 코드 분기를 늘리지 않고 여기만 고친다.</p>
 */
public final class SecurityConstants {

    /** 마스킹 자리표시자. 길이·형식을 보존하지 않아 원문 복원이 불가능하다. */
    public static final String MASK = "***";

    /** 정확히 이 이름(소문자, {@code _ -} 제거)이면 값 전체를 마스킹한다. */
    public static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "clientsecret", "privatekey",
            "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "bearertoken",
            "apikey", "xapikey", "authorization", "proxyauthorization",
            "credential", "credentials", "cookie", "setcookie");

    /** 접미사로 시크릿임을 알 수 있는 키. (예: k8sToken, webhookSecret) */
    public static final List<String> SENSITIVE_KEY_SUFFIXES = List.of(
            "token", "secret", "password", "apikey", "credential");

    /** auth_config 에 값으로 들어오면 거부하는 키 (환경변수 이름만 허용). */
    public static final Set<String> AUTH_CONFIG_LITERAL_KEYS = Set.of(
            "value", "secret", "token", "password", "apikey", "api_key", "credential");

    /** 스킴·설정과 무관하게 항상 차단하는 메타데이터 주소. */
    public static final List<String> ALWAYS_BLOCKED_HOSTS = List.of(
            "169.254.169.254",          // AWS/GCP/Azure 공통 메타데이터
            "metadata.google.internal",
            "metadata.goog",
            "100.100.100.200",          // Alibaba Cloud 메타데이터
            "fd00:ec2::254");           // AWS IPv6 메타데이터

    private SecurityConstants() {
    }
}
