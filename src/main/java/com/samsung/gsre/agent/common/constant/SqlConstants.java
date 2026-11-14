package com.samsung.gsre.agent.common.constant;

import java.util.List;

/**
 * SQL 안전 규칙에서 공유하는 고정 키워드·목록.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <p>파괴적 키워드, 시스템 스키마, FROM 절 종료 키워드, 사용자 마스터 테이블 이름.
 * {@code gateway.guard.SqlGuard} 와 {@code mcp.validation.SourceQueryValidator} 가
 * 같은 목록을 서로 다르게 관리하지 않도록 한 곳에 둔다.</p>
 *
 * <h2>중단·하단</h2>
 * <p>값만 제공한다. 정규식 조립은 각 검증기가 한다(대소문자·경계 조건은 문맥에 따라 다르다).</p>
 */
public final class SqlConstants {

    /** 변경·실행·파일 접근 키워드 (정규식 본문). 양쪽 검증기가 같은 목록을 쓴다. */
    public static final String DESTRUCTIVE_KEYWORDS =
            "insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute"
                    + "|replace|into|runscript|shutdown|backup|load|handler|rename|unlock|lock";

    /** 접근 금지 시스템 스키마. */
    public static final List<String> SYSTEM_SCHEMAS = List.of(
            "information_schema", "sys", "pg_catalog", "mysql", "performance_schema");

    /** FROM 절의 테이블 목록이 끝나는 키워드. (묵시적 조인 검사에 사용) */
    public static final List<String> FROM_END_KEYWORDS = List.of(
            "where", "group", "order", "having", "limit", "union", "join", "on", "intersect", "except");

    /** 개인정보를 담고 있어 별도 정책(MASK/DENY)이 적용되는 사용자 마스터 테이블. */
    public static final String USER_MASTER_TABLE = "svcm_tb_user_info_master";

    /** 사용자 마스터 정책 값 (agent.guard.user-master-mode). */
    public static final String USER_MASTER_MODE_MASK = "MASK";
    public static final String USER_MASTER_MODE_DENY = "DENY";
    public static final String USER_MASTER_MODE_ALLOW = "ALLOW";

    private SqlConstants() {
    }
}
