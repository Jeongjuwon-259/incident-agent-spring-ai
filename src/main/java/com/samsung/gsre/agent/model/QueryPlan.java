package com.samsung.gsre.agent.model;

import java.util.List;

/** 1단계(질의 이해) 산출물. 멀티턴 맥락까지 반영한 정규화 질의와 조회 작업 목록. */
public record QueryPlan(
        String normalizedQuestion,
        Intent intent,
        List<String> appIds,
        List<String> keywords,
        List<LookupTask> tasks,
        boolean multiStep,
        boolean followUp,
        String reasoning) {

    public QueryPlan {
        appIds = appIds == null ? List.of() : List.copyOf(appIds);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
