package com.samsung.gsre.agent.security.net;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samsung.gsre.agent.config.AgentProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 테이블 기반 외부 호출의 SSRF 정책 검증. */
class OutboundTargetPolicyTest {

    private AgentProperties props;
    private OutboundTargetPolicy policy;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.getMcp().getNetwork().setAllowLocalEndpoints(true);
        policy = new OutboundTargetPolicy(props);
    }

    @Test
    @DisplayName("http(s) 외 스킴과 사용자정보가 든 URL 은 차단한다")
    void rejectsSchemeAndUserInfo() {
        assertThatThrownBy(() -> policy.verify("file:///etc/passwd"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> policy.verify("http://user:pass@example.com/api"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("사용자정보");
    }

    @Test
    @DisplayName("클라우드 메타데이터 주소는 로컬 허용 설정과 무관하게 항상 차단한다")
    void alwaysBlocksMetadata() {
        assertThatThrownBy(() -> policy.verify("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> policy.verify("http://metadata.google.internal/computeMetadata/v1"))
                .isInstanceOf(OutboundTargetBlockedException.class);
    }

    @Test
    @DisplayName("로컬 허용이 켜져 있으면 루프백(데모 시드)은 통과한다")
    void allowsLoopbackWhenConfigured() {
        assertThatCode(() -> policy.verify("http://127.0.0.1:18080/api/cmdb/apps/APP-1001"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.verify("http://localhost:18080/mcp/k8s"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("로컬 허용을 끄면 루프백·사설 대역을 차단한다")
    void blocksLocalAndPrivateWhenDisallowed() {
        props.getMcp().getNetwork().setAllowLocalEndpoints(false);

        assertThatThrownBy(() -> policy.verify("http://127.0.0.1:18080/api/x"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("allow-local-endpoints");
        assertThatThrownBy(() -> policy.verify("http://10.0.0.5/api/x"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("사설");
        assertThatThrownBy(() -> policy.verify("http://192.168.0.10/api/x"))
                .isInstanceOf(OutboundTargetBlockedException.class);
    }

    @Test
    @DisplayName("허용 목록이 있으면 그 호스트(서브도메인 포함)만 통과한다")
    void enforcesAllowList() {
        props.getMcp().getNetwork().setAllowedHosts(List.of("api.example.com"));

        assertThatThrownBy(() -> policy.verify("http://127.0.0.1:18080/api/x"))
                .isInstanceOf(OutboundTargetBlockedException.class)
                .hasMessageContaining("허용 목록");
        assertThatCode(() -> policy.verify("https://api.example.com/v1/health"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.verify("https://sub.api.example.com/v1/health"))
                .doesNotThrowAnyException();
    }
}
