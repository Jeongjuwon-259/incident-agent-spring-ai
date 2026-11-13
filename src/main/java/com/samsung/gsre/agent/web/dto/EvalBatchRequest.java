package com.samsung.gsre.agent.web.dto;

import java.util.List;

/** 평가 실행 요청. all=true 면 사용 중인 전체 TC, 아니면 tcIds 만 평가한다. */
public record EvalBatchRequest(List<String> tcIds, Boolean all, String requestedBy) {
}
