package com.samsung.gsre.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 에이전트 동작 정책. 모든 값은 application.yml / 환경변수로 교체 가능하다.
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

    @Getter
    @Setter
    public static class Llm {
        /** local = Spring AI ChatClient 로 로컬 LLM 호출, offline = 규칙 기반 결정적 대체 구현 */
        private String mode = "local";
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 120_000;
        private int maxTokens = 1_200;

        public boolean isOffline() {
            return "offline".equalsIgnoreCase(mode);
        }
    }

    @Getter
    @Setter
    public static class Pipeline {
        private int maxTasks = 5;
        /** 답변 생성 후 추가 조회를 허용하는 라운드 수 (Multi-step Query) */
        private int maxExtraRounds = 2;
        /** 한 질의에서 실행 가능한 최대 SQL 수 (무한 반복 방지) */
        private int maxExecutions = 6;
        /** 한 질의에서 허용하는 최대 LLM 호출 수 (비용·지연 상한) */
        private int maxLlmCalls = 24;
        /** Self-Repair 재시도 횟수 */
        private int maxRepairAttempts = 2;
        /** 멀티턴 맥락으로 넘겨줄 최근 대화 턴 수 */
        private int historyTurns = 6;
    }

    @Getter
    @Setter
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.80;
        /** auto | embedding | lexical */
        private String similarityMode = "auto";
        private boolean embeddingEnabled = true;
    }

    @Getter
    @Setter
    public static class Guard {
        private int maxRows = 200;
        private int queryTimeoutSeconds = 5;
        private boolean maskPii = true;
        /** MASK | DENY | ALLOW */
        private String userMasterMode = "MASK";
    }

    @Getter
    @Setter
    public static class Eval {
        private int maxCasesPerBatch = 100;
        private int judgeTruncateRows = 30;
    }

    @Getter
    @Setter
    public static class Demo {
        private boolean seed = true;
    }
}
