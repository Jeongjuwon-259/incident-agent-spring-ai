package com.samsung.gsre.agent.security.mask;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 로그/감사 테이블에 남기기 전 시크릿·PII 마스킹 규칙 검증. */
class SensitiveDataMaskerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("JSON 의 시크릿 키는 값 전체를 가리고, 일반 필드는 그대로 둔다")
    void masksSecretKeysInJson() throws Exception {
        String raw = """
                {"apiKey":"sk-live-1234567890","appId":"APP-1001",
                 "auth":{"password":"p@ssw0rd","client_secret":"cs-999"},
                 "summary":"배포 실패"}
                """;

        String masked = SensitiveDataMasker.maskJson(raw);

        assertThat(masked).doesNotContain("sk-live-1234567890")
                .doesNotContain("p@ssw0rd")
                .doesNotContain("cs-999")
                .contains("APP-1001")
                .contains("배포 실패")
                .contains("***");
        // 마스킹 후에도 JSON 문법이 유지되어야 한다.
        assertThat(mapper.readTree(masked).path("appId").asText()).isEqualTo("APP-1001");
    }

    @Test
    @DisplayName("본문에 섞인 Bearer 토큰·이메일·전화번호·주민등록번호를 부분 마스킹한다")
    void masksTokensAndPiiInText() {
        String raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature "
                + "owner=kim.minsoo@samsung.com phone=010-1234-5678 rrn=900101-1234567";

        String masked = SensitiveDataMasker.maskText(raw);

        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("kim.minsoo")
                .doesNotContain("1234-5678")
                .doesNotContain("1234567")
                .contains("***")
                .contains("ki***@samsung.com")
                .contains("010-****-5678");
        assertThat(SensitiveDataMasker.maskText("Bearer abcdef123456")).isEqualTo("Bearer ***");
    }

    @Test
    @DisplayName("시크릿이 없는 텍스트는 변형하지 않는다")
    void keepsSafeText() {
        String raw = "APP-1001 서비스맵포털 담당자는 김민수(SRE플랫폼팀)입니다.";

        assertThat(SensitiveDataMasker.maskText(raw)).isEqualTo(raw);
        assertThat(SensitiveDataMasker.maskJson("{\"appId\":\"APP-1001\"}"))
                .isEqualTo("{\"appId\":\"APP-1001\"}");
    }

    @Test
    @DisplayName("JSON 노드 마스킹은 원본을 바꾸지 않는다")
    void maskingNodeDoesNotMutateSource() throws Exception {
        var original = mapper.readTree("{\"token\":\"abc123456\",\"appId\":\"APP-1001\"}");

        var masked = SensitiveDataMasker.mask(original);

        assertThat(original.path("token").asText()).isEqualTo("abc123456");
        assertThat(masked.path("token").asText()).isEqualTo("***");
    }
}
