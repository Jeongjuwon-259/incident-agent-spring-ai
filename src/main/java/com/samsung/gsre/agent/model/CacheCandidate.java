package com.samsung.gsre.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 검증 SQL 시맨틱 캐시 검색 후보. 유사도는 참고값일 뿐이며,
 * 재사용 확정은 3단계(재사용 판단)에서 조회 대상·조건·파라미터 적합성을 확인해 내린다.
 */
public record CacheCandidate(
        long id,
        String question,
        String sql,
        double score,
        String scoreType,
        List<String> tables,
        String verifiedBy,
        Map<String, String> defaultParams,
        String notes) {

    public CacheCandidate {
        tables = tables == null ? List.of() : List.copyOf(tables);
        defaultParams = defaultParams == null ? Map.of() : Map.copyOf(defaultParams);
    }
}
