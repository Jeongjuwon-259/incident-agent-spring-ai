package com.samsung.gsre.agent.gateway.guard;

import java.util.List;

/**
 * SQL 안전 검증 결과.
 *
 * @param valid      실행 가능 여부
 * @param sql        정규화된 실행 SQL (LIMIT 보정 반영)
 * @param violations 위반 사항 (valid=false 일 때 사유)
 * @param repairable Self-Repair 로 고칠 수 있는 위반인지 (보안 위반은 false)
 * @param tables     접근하는 테이블 목록
 * @param maskPii    결과 마스킹이 필요한지
 */
public record GuardVerdict(
        boolean valid,
        String sql,
        List<String> violations,
        boolean repairable,
        List<String> tables,
        boolean maskPii) {

    public GuardVerdict {
        violations = violations == null ? List.of() : List.copyOf(violations);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public static GuardVerdict ok(String sql, List<String> tables, boolean maskPii) {
        return new GuardVerdict(true, sql, List.of(), false, tables, maskPii);
    }

    public static GuardVerdict reject(List<String> violations, boolean repairable) {
        return new GuardVerdict(false, null, violations, repairable, List.of(), false);
    }
}
