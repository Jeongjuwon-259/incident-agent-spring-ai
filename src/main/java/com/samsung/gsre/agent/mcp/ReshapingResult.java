package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Reshaping 결과 + 다이어트 통계.
 *
 * <p>{@code sourceCount → targetCount} 와 {@code droppedCount} 가 "얼마나 압축했는지" 를 보여준다.
 * 로그·콘솔에서 Raw 크기와 함께 보면 룰이 실제로 일하고 있는지 바로 판단할 수 있다.</p>
 */
public record ReshapingResult(
        JsonNode payload,
        int sourceCount,
        int targetCount,
        int droppedCount,
        List<String> warnings) {
}
