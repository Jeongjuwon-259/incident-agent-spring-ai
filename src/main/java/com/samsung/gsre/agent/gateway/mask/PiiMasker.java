package com.samsung.gsre.agent.gateway.mask;

import java.math.BigDecimal;

/**
 * 조회 결과의 PII 컬럼 마스킹 규칙.
 *
 * <h2>상단 (INPUT) — 기준정보</h2>
 * <ul>
 *   <li>이메일: 로컬파트 앞 2자만 남기고 도메인은 유지한다.</li>
 *   <li>연락처(phone/tel): 앞 3자리와 뒤 4자리만 남긴다.</li>
 *   <li>사번(emp_no/employee): 앞 2자만 남긴다.</li>
 *   <li>그 외 PII 플래그 컬럼(성명 등)은 원문을 유지한다 — 답변에 필요한 최소 정보이기 때문이다.</li>
 * </ul>
 *
 * <h2>중단 (PROCESS) — 마스킹</h2>
 * <p>컬럼 이름과 값 모양으로 규칙을 고른다.</p>
 *
 * <h2>하단 (OUTPUT) — 결과</h2>
 * <p>마스킹된 값. {@code null}/공백은 그대로 돌려준다.</p>
 */
public final class PiiMasker {

    private PiiMasker() {
    }

    public static Object mask(String column, Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
        if (text.isBlank()) {
            return value;
        }
        if (text.contains("@")) {
            int at = text.indexOf('@');
            String local = text.substring(0, at);
            String domain = text.substring(at);
            String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
            return visible + "***" + domain;
        }
        if (column != null && (column.contains("phone") || column.contains("tel"))) {
            String digits = text.replaceAll("[^0-9]", "");
            if (digits.length() >= 7) {
                return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
            }
        }
        if (column != null && (column.contains("emp_no") || column.contains("employee"))) {
            return text.length() <= 2 ? text : text.substring(0, 2) + "****";
        }
        return text;
    }
}
