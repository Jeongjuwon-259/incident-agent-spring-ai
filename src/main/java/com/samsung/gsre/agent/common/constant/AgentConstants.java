package com.samsung.gsre.agent.common.constant;

import java.time.format.DateTimeFormatter;

/**
 * 프로젝트 전역 기본값·형식 상수.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <p>여러 도메인에서 반복되는 숫자·형식만 둔다. 도메인에만 의미가 있는 값은 해당 도메인의
 * `common/constant` 하위(예: {@link McpConstants}, {@link SqlConstants})에 둔다.</p>
 *
 * <h2>중단·하단</h2>
 * <p>이 클래스는 값만 제공한다(로직 없음). 값을 바꾸면 {@code application.yml} 기본값과
 * 문서(README §10)도 함께 맞춘다.</p>
 */
public final class AgentConstants {

    // ---------- 식별자·시간 형식 ----------
    /** RUN-/EVAL-/MCP- 접두어 뒤에 붙는 시각 형식 (초 단위). */
    public static final String ID_TIMESTAMP_PATTERN = "yyyyMMddHHmmss";
    /** 위 패턴의 공유 포매터. DateTimeFormatter 는 불변·스레드 안전이다. */
    public static final DateTimeFormatter ID_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern(ID_TIMESTAMP_PATTERN);
    /** 실행/세션/평가/호출 ID 접두어. */
    public static final String RUN_ID_PREFIX = "RUN-";
    public static final String SESSION_ID_PREFIX = "SESSION-";
    public static final String EVAL_BATCH_PREFIX = "EVAL-";
    public static final String MCP_CALL_PREFIX = "MCP-";
    /** 식별자·채널 기본값. */
    public static final String ANONYMOUS_USER = "anonymous";
    public static final String DEFAULT_CHANNEL = "WEB";

    // ---------- 파이프라인 기본값 (agent.pipeline.*) ----------
    public static final int DEFAULT_MAX_TASKS = 5;
    public static final int DEFAULT_MAX_EXTRA_ROUNDS = 2;
    public static final int DEFAULT_MAX_EXECUTIONS = 6;
    public static final int DEFAULT_MAX_LLM_CALLS = 24;
    public static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 2;
    public static final int DEFAULT_HISTORY_TURNS = 6;
    /** 한 질의에서 6단계 루프를 도는 최대 횟수 (무한 루프 방지 안전장치). */
    public static final int DEFAULT_MAX_FLOW_ITERATIONS = 30;

    // ---------- RAG 기본값 (agent.rag.*) ----------
    public static final int DEFAULT_RAG_TOP_K = 5;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.80;

    // ---------- SQL 가드 기본값 (agent.guard.*) ----------
    public static final int DEFAULT_GUARD_MAX_ROWS = 200;
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 5;

    // ---------- 품질 평가 기본값 (agent.eval.*) ----------
    public static final int DEFAULT_EVAL_MAX_CASES_PER_BATCH = 100;
    public static final int DEFAULT_JUDGE_TRUNCATE_ROWS = 30;

    // ---------- LLM 기본값 (agent.llm.*) ----------
    public static final int DEFAULT_LLM_MAX_TOKENS = 1_200;
    public static final int DEFAULT_LLM_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_LLM_READ_TIMEOUT_MS = 120_000;
    /** 로컬 LLM(OpenAI 호환) 기본 엔드포인트. */
    public static final String DEFAULT_LOCAL_LLM_BASE_URL = "http://localhost:11434/v1";

    // ---------- MCP 기본값 (agent.mcp.*) ----------
    public static final int DEFAULT_MCP_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MCP_CONNECT_TIMEOUT_MS = 2_000;
    public static final int DEFAULT_MCP_READ_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MCP_MAX_SOURCE_ROWS = 100;
    public static final int DEFAULT_MCP_MAX_LOG_PAYLOAD_CHARS = 20_000;
    /** 감사 로그 payload 의 최소 상한. 설정이 이보다 작아도 이 값까지는 남긴다. */
    public static final int MIN_LOG_PAYLOAD_CHARS = 500;

    // ---------- 로그·조회 크기 ----------
    public static final int DEFAULT_LOG_MESSAGE_MAX_CHARS = 1_000;
    public static final int DEFAULT_RUN_QUESTION_MAX_CHARS = 2_000;
    public static final int DEFAULT_WEB_PREVIEW_ROWS = 5;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private AgentConstants() {
    }
}
