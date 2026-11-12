package io.github.cn3shy.chatshy.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 兜底异常：已提交的响应（SSE/流式）不能再改，直接让容器收尾。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(NoResourceFoundException error, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        return ResponseEntity.status(404).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception error, HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("响应已提交后发生异常（多半是调用方断开）：{}", error.toString());
            return null;
        }
        log.error("未处理异常：{} {}", request.getMethod(), request.getRequestURI(), error);
        String path = request.getRequestURI();
        if (path.startsWith("/v1/") || path.startsWith("/api/")) {
            return ResponseEntity.status(500).body(Map.of("error", Map.of("message", "服务端异常")));
        }
        return ResponseEntity.status(500).body(Map.of("error", "internal"));
    }
}
