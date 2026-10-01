package com.onecare.backend.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.onecare.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
@Hidden
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(ResourceNotFoundException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidationException(
            MethodArgumentNotValidException ex) {

        List<Map<String, String>> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> {
                    Map<String, String> fieldError = new HashMap<>();
                    fieldError.put("field", error.getField());
                    fieldError.put(
                            "message",
                            error.getDefaultMessage()
                    );
                    return fieldError;
                }).toList();

        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(
                        false,
                        "Request validation failed",
                        errors
                ));
    }

    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ApiResponse<?>> handleInvalidResetToken(InvalidResetTokenException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<ApiResponse<?>> handleEmailSendFailure(EmailSendException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, "Unable to send email at this time. Please try again later.");

        return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<?>> handleBadCredentials(BadCredentialsException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, "Invalid username or password");

        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccountLocked(AccountLockedException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.LOCKED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, "Access denied: insufficient permissions");

        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception ex) {

        ApiResponse<?> response = new ApiResponse<>(false, "Internal Server Error");

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
        @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ApiResponse<?>> handleDuplicateUser(DuplicateUserException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessRule(BusinessRuleException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ApiResponse<?>> handleUnreadableBody(
        HttpMessageNotReadableException ex) {

    String message = "Malformed or unreadable request body";

    if (ex.getCause() instanceof InvalidFormatException ife
            && ife.getTargetType() != null
            && ife.getTargetType().isEnum()) {

        String field = ife.getPath().isEmpty()
                ? "value"
                : ife.getPath()
                    .get(ife.getPath().size() - 1)
                    .getFieldName();

        message = "Invalid value '" + ife.getValue()
                + "' for field '" + field
                + "'. Allowed values: "
                + Arrays.toString(
                    ife.getTargetType().getEnumConstants()
                );
    }

    return new ResponseEntity<>(
            new ApiResponse<>(false, message),
            HttpStatus.BAD_REQUEST
    );
}

}
