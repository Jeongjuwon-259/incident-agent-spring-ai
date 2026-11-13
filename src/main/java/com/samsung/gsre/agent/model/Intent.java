package com.samsung.gsre.agent.model;

/** 질의 의도 분류. 조회 목적별 SQL 템플릿 선택과 재사용 판단의 기준이 된다. */
public enum Intent {
    APP_INFO,
    CONTACT,
    USER_ACCESS,
    DEPT,
    SERVICE_MAP,
    JIRA_WORKLOG,
    ALIAS,
    COUNT,
    GENERAL
}
