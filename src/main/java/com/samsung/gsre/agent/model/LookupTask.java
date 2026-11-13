package com.samsung.gsre.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 조회 계획의 최소 단위. 질의 하나가 여러 번의 조회로 나뉠 때 각 단계가 이 단위로 실행된다.
 *
 * @param id           실행 계획 내 식별자 (T1, T2 ...)
 * @param purpose      조회 목적 (사람이 읽는 문장)
 * @param intent       의도 분류
 * @param targetTables 이 조회가 건드릴 것으로 예상되는 테이블
 * @param filters      질의에서 확정된 조건 (appId, deptName ...)
 */
public record LookupTask(
        String id,
        String purpose,
        Intent intent,
        List<String> targetTables,
        Map<String, String> filters) {

    public LookupTask {
        targetTables = targetTables == null ? List.of() : List.copyOf(targetTables);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    public String appId() {
        return filters.get("appId");
    }
}
