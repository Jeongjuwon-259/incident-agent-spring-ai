package com.samsung.gsre.agent.config;

import com.samsung.gsre.agent.common.constant.AgentConstants;
import com.samsung.gsre.agent.common.constant.SqlConstants;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 에이전트 동작 정책. 모든 값은 application.yml / 환경변수로 교체 가능하다.
 *
 * <p>코드 측 기본값은 {@link AgentConstants} 에 모아 둔다. (설정 파일과 코드가 서로 다른
 * 숫자를 들고 있지 않도록)</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Pipeline pipeline = new Pipeline();
    private Rag rag = new Rag();
    private Guard guard = new Guard();
    private Eval eval = new Eval();
    private Demo demo = new Demo();
    private Mcp mcp = new Mcp();

    @Getter
    @Setter
    public static class Llm {
        /** local = Spring AI ChatClient 로 로컬 LLM 호출, offline = 규칙 기반 결정적 대체 구현 */
        private String mode = "local";
        private int connectTimeoutMs = AgentConstants.DEFAULT_LLM_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = AgentConstants.DEFAULT_LLM_READ_TIMEOUT_MS;
        private int maxTokens = AgentConstants.DEFAULT_LLM_MAX_TOKENS;

        public boolean isOffline() {
            return "offline".equalsIgnoreCase(mode);
        }
    }

    @Getter
    @Setter
    public static class Pipeline {
        private int maxTasks = AgentConstants.DEFAULT_MAX_TASKS;
        /** 답변 생성 후 추가 조회를 허용하는 라운드 수 (Multi-step Query) */
        private int maxExtraRounds = AgentConstants.DEFAULT_MAX_EXTRA_ROUNDS;
        /** 한 질의에서 실행 가능한 최대 SQL 수 (무한 반복 방지) */
        private int maxExecutions = AgentConstants.DEFAULT_MAX_EXECUTIONS;
        /** 한 질의에서 허용하는 최대 LLM 호출 수 (비용·지연 상한) */
        private int maxLlmCalls = AgentConstants.DEFAULT_MAX_LLM_CALLS;
        /** Self-Repair 재시도 횟수 */
        private int maxRepairAttempts = AgentConstants.DEFAULT_MAX_REPAIR_ATTEMPTS;
        /** 멀티턴 맥락으로 넘겨줄 최근 대화 턴 수 */
        private int historyTurns = AgentConstants.DEFAULT_HISTORY_TURNS;
    }

    @Getter
    @Setter
    public static class Rag {
        private int topK = AgentConstants.DEFAULT_RAG_TOP_K;
        private double similarityThreshold = AgentConstants.DEFAULT_SIMILARITY_THRESHOLD;
        /** auto | embedding | lexical */
        private String similarityMode = "auto";
        private boolean embeddingEnabled = true;
    }

    @Getter
    @Setter
    public static class Guard {
        private int maxRows = AgentConstants.DEFAULT_GUARD_MAX_ROWS;
        private int queryTimeoutSeconds = AgentConstants.DEFAULT_QUERY_TIMEOUT_SECONDS;
        private boolean maskPii = true;
        /** MASK | DENY | ALLOW */
        private String userMasterMode = SqlConstants.USER_MASTER_MODE_MASK;
    }

    @Getter
    @Setter
    public static class Eval {
        private int maxCasesPerBatch = AgentConstants.DEFAULT_EVAL_MAX_CASES_PER_BATCH;
        private int judgeTruncateRows = AgentConstants.DEFAULT_JUDGE_TRUNCATE_ROWS;
    }

    @Getter
    @Setter
    public static class Demo {
        private boolean seed = true;
    }

    @Getter
    @Setter
    public static class Mcp {
        /** AUTO = 실제 호출 후 실패 시 픽스처 폴백, OFFLINE = 네트워크 없이 픽스처만, HTTP = 폴백 없이 실제 호출만 */
        private String transportMode = "AUTO";
        private int connectTimeoutMs = AgentConstants.DEFAULT_MCP_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = AgentConstants.DEFAULT_MCP_READ_TIMEOUT_MS;
        /** INBOUND 원천 SELECT 결과 상한 (메모리·로그 보호) */
        private int maxSourceRows = AgentConstants.DEFAULT_MCP_MAX_SOURCE_ROWS;
        /** 감사 로그에 저장하는 payload 최대 길이 (초과분은 잘라내고 표시) */
        private int maxLogPayloadChars = AgentConstants.DEFAULT_MCP_MAX_LOG_PAYLOAD_CHARS;
        /** 실제 엔드포인트 연결 실패 시 결정적 픽스처로 대체할지 여부 (AUTO 모드) */
        private boolean offlineFallbackEnabled = true;
        /** 외부 엔드포인트 네트워크 정책 (SSRF 방어) */
        private Network network = new Network();

        /**
         * 외부 호출 대상 정책. 기본값은 로컬 데모(시드가 localhost:18080)를 위해 로컬 허용이고,
         * 운영에서는 {@code allow-local-endpoints=false} + {@code allowed-hosts} 지정을 권장한다.
         */
        @Getter
        @Setter
        public static class Network {
            /** true면 루프백·사설 대역 호출을 허용한다. (로컬 데모 기본값) */
            private boolean allowLocalEndpoints = true;
            /** 비어 있지 않으면 이 호스트(또는 *.서브도메인)만 허용한다. */
            private List<String> allowedHosts = new ArrayList<>();
            /** 호스트 이름 기준 추가 차단 목록. (메타데이터 주소는 코드에서 항상 차단) */
            private List<String> blockedHosts = new ArrayList<>();
        }
    }
}
