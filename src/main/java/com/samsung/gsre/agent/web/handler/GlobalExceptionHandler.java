package com.samsung.gsre.agent.web.handler;

import com.samsung.gsre.agent.mcp.exception.McpApiNotFoundException;
import com.samsung.gsre.agent.mcp.exception.McpTransportException;
import com.samsung.gsre.agent.security.net.OutboundTargetBlockedException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 오류 응답을 한 곳에서 처리한다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** SSRF/네트워크 정책 위반 (등록 시점). 실행 중에는 FAILED 결과 + 감사 로그로 남는다. */
    @ExceptionHandler(OutboundTargetBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlockedTarget(OutboundTargetBlockedException e) {
        return ResponseEntity.badRequest().body(body("OUTBOUND_TARGET_BLOCKED", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", e.getMessage()));
    }

    /** 등록되지 않은 MCP API 실행. */
    @ExceptionHandler(McpApiNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMcpNotFound(McpApiNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("MCP_API_NOT_FOUND", e.getMessage()));
    }

    /** 실제 MCP 엔드포인트 통신 실패 (폴백 비활성/HTTP 모드). */
    @ExceptionHandler(McpTransportException.class)
    public ResponseEntity<Map<String, Object>> handleMcpTransport(McpTransportException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body("MCP_TRANSPORT_ERROR", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> body = body("VALIDATION_ERROR", "입력값을 확인해 주세요.");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("[api] 처리되지 않은 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "서버 오류가 발생했습니다. runId 와 함께 관리자에게 문의해 주세요."));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
