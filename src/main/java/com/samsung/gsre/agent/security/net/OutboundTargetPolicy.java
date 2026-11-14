package com.samsung.gsre.agent.security.net;

import com.samsung.gsre.agent.common.constant.SecurityConstants;
import com.samsung.gsre.agent.common.validation.ValidationUtils;
import com.samsung.gsre.agent.config.AgentProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테이블에 등록된 외부 엔드포인트로 나가기 전에 통과해야 하는 단일 네트워크 정책 (SSRF 방어).
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>스킴은 {@code http}/{@code https} 만 허용한다.</li>
 *   <li>URL 에 사용자정보({@code user:pass@host})를 넣을 수 없다.</li>
 *   <li>메타데이터·링크로컬·멀티캐스트·미지정 주소는 <b>항상</b> 차단한다. (169.254.169.254 등)</li>
 *   <li>루프백·사설 대역은 {@code agent.mcp.network.allow-local-endpoints=false} 일 때 차단한다.</li>
 *   <li>{@code agent.mcp.network.allowed-hosts} 가 있으면 그 목록에 있는 호스트만 허용한다.</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 검증</h2>
 * <ol>
 *   <li>URI 문법·스킴·사용자정보·호스트 확인</li>
 *   <li>차단 목록(호스트 이름) 확인</li>
 *   <li>DNS 해석 후 실제 IP 대역 확인 (DNS 로 사설 IP 로 우회하는 경우 차단)</li>
 *   <li>허용 목록 확인 (설정된 경우)</li>
 * </ol>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>통과하면 아무것도 하지 않고, 위반이면 {@link OutboundTargetBlockedException} 을 던진다.
 * 이 검증은 등록 시점과 실제 전송 직전에 <b>두 번</b> 수행한다 — 테이블은 API 밖에서도 수정될 수 있기 때문이다.</p>
 */
@Slf4j
@Component
public class OutboundTargetPolicy {

    private final AgentProperties props;

    public OutboundTargetPolicy(AgentProperties props) {
        this.props = props;
    }

    // ==================================================================
    // 중단 (PROCESS) — 검증 진입점
    // ==================================================================

    /**
     * 엔드포인트 URL 이 네트워크 정책을 통과하는지 확인한다.
     *
     * @throws OutboundTargetBlockedException 스킴·호스트·대역·허용목록 위반
     */
    public void verify(String endpointUrl) {
        URI uri = parse(endpointUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new OutboundTargetBlockedException(
                    "http(s) 스킴만 허용합니다. (지정값=" + (scheme.isBlank() ? "없음" : scheme) + ")");
        }
        if (ValidationUtils.hasText(uri.getRawUserInfo())) {
            throw new OutboundTargetBlockedException(
                    "URL 에 사용자정보(user:password@host)를 넣을 수 없습니다. 인증은 auth_config(환경변수)를 사용하세요.");
        }
        String host = uri.getHost();
        if (ValidationUtils.isBlank(host)) {
            throw new OutboundTargetBlockedException("호스트가 없는 URL 입니다.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");

        AgentProperties.Mcp.Network network = props.getMcp().getNetwork();
        if (matchesAny(normalizedHost, SecurityConstants.ALWAYS_BLOCKED_HOSTS)) {
            throw new OutboundTargetBlockedException("클라우드 메타데이터 주소로는 호출할 수 없습니다: " + normalizedHost);
        }
        if (matchesAny(normalizedHost, network.getBlockedHosts())) {
            throw new OutboundTargetBlockedException("차단 목록에 있는 호스트입니다: " + normalizedHost);
        }

        List<InetAddress> addresses = resolve(normalizedHost);
        boolean localName = "localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost");

        for (InetAddress address : addresses) {
            String reason = blockedReason(address, normalizedHost);
            if (reason != null && (!network.isAllowLocalEndpoints() || "ALWAYS".equals(reason))) {
                throw new OutboundTargetBlockedException(reason.equals("ALWAYS")
                        ? "링크로컬/멀티캐스트/미지정 주소로는 호출할 수 없습니다: " + address.getHostAddress()
                        : "로컬/사설 대역 주소로는 호출할 수 없습니다(allow-local-endpoints=false): "
                                + address.getHostAddress());
            }
        }
        if (!network.isAllowLocalEndpoints() && localName) {
            throw new OutboundTargetBlockedException("로컬 호스트로는 호출할 수 없습니다(allow-local-endpoints=false).");
        }
        if (!network.getAllowedHosts().isEmpty()) {
            boolean allowed = matchesAny(normalizedHost, network.getAllowedHosts())
                    || addresses.stream().anyMatch(a -> matchesAny(a.getHostAddress(), network.getAllowedHosts()));
            if (!allowed) {
                throw new OutboundTargetBlockedException(
                        "허용 목록(allowed-hosts)에 없는 호스트입니다: " + normalizedHost);
            }
        }
    }

    // ==================================================================
    // 하단 (OUTPUT) — 판정 유틸
    // ==================================================================

    /** @return "ALWAYS"(항상 차단), "LOCAL"(로컬 설정에서만 차단), null(통과) */
    private String blockedReason(InetAddress address, String host) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) {
            return "ALWAYS";
        }
        if (isUniqueLocalIpv6(address)) {
            return "LOCAL";
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return "LOCAL";
        }
        log.debug("[net] 대상 허용 host={} ip={}", host, address.getHostAddress());
        return null;
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private URI parse(String endpointUrl) {
        if (ValidationUtils.isBlank(endpointUrl)) {
            throw new OutboundTargetBlockedException("엔드포인트 URL 이 비어 있습니다.");
        }
        try {
            // 경로변수 템플릿({appId} 등)은 등록 시점에 실제 값이 없으므로 자리표시자로 바꿔 문법만 본다.
            String candidate = endpointUrl.trim().replaceAll("\\{[^}]*}", "x");
            URI uri = URI.create(candidate);
            if (!uri.isAbsolute()) {
                throw new OutboundTargetBlockedException("절대 URL 이 아닙니다: " + endpointUrl);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            if (e instanceof OutboundTargetBlockedException blocked) {
                throw blocked;
            }
            throw new OutboundTargetBlockedException("URL 형식이 올바르지 않습니다: " + endpointUrl);
        }
    }

    /** 호스트 목록 매칭. {@code *.example.com} 은 서브도메인 전체를 뜻한다. */
    private boolean matchesAny(String host, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String raw : patterns) {
            if (ValidationUtils.isBlank(raw)) {
                continue;
            }
            String pattern = raw.trim().toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (host.endsWith(suffix)) {
                    return true;
                }
            } else if (host.equals(pattern) || host.endsWith("." + pattern)) {
                return true;
            }
        }
        return false;
    }

    /** DNS 해석 실패는 등록 단계에서 흔하므로 차단 사유로 쓰지 않는다. (전송 시점에 다시 실패한다) */
    private List<InetAddress> resolve(String host) {
        if (isIpLiteral(host)) {
            try {
                return List.of(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                throw new OutboundTargetBlockedException("IP 주소를 해석할 수 없습니다: " + host);
            }
        }
        try {
            return new ArrayList<>(List.of(InetAddress.getAllByName(host)));
        } catch (UnknownHostException e) {
            log.debug("[net] 호스트를 해석하지 못했습니다(등록 허용): {}", host);
            return List.of();
        }
    }

    private boolean isIpLiteral(String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }
}
