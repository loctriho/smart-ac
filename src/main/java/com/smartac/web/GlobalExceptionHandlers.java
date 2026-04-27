package com.smartac.web;

import com.smartac.device.service.RateLimitException;
import com.smartac.device.service.ServiceOverloadedException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandlers {

  @ExceptionHandler(RateLimitException.class)
  public ResponseEntity<Map<String, String>> rateLimit(RateLimitException ex) {
    Map<String, String> body = new HashMap<>();
    body.put("error", ex.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
  }

  @ExceptionHandler(ServiceOverloadedException.class)
  public ResponseEntity<Map<String, String>> overloaded(ServiceOverloadedException ex) {
    Map<String, String> body = new HashMap<>();
    body.put("error", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException ex) {
    Map<String, String> body = new HashMap<>();
    body.put("error", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
    Map<String, Object> body = new HashMap<>();
    body.put("error", msg);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }
}
