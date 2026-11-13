package com.samsung.gsre.agent.nl2sql;

/** 컬럼 메타데이터. pii=true 인 컬럼은 결과 조회 시 마스킹 대상이다. */
public record ColumnMetadata(String name, String type, String description, boolean pii) {
}
