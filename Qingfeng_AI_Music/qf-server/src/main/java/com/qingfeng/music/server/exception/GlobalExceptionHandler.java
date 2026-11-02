package com.qingfeng.music.server.exception;

import com.qingfeng.music.common.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse("INVALID_AUDIO_SIZE", "音频不能超过20MB"));
    }
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> domain(DomainException error) {
        HttpStatus status = error.code().endsWith("UNAVAILABLE") ? HttpStatus.SERVICE_UNAVAILABLE : error.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : error.code().startsWith("INVALID_") ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), error.getMessage()));
    }
    public record ErrorResponse(String code, String message) {}
}
