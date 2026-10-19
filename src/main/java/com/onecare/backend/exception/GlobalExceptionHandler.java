package com.onecare.backend.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.onecare.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
@Hidden
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        ApiResponse<?> response = new ApiResponse<>(false,
                "Unable to send email at this time. Please try again later.");

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

    @ExceptionHandler(InvalidAppointmentStatusException.class)
    public ResponseEntity<ApiResponse<?>> handleInvalidAppointmentStatus(InvalidAppointmentStatusException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SlotConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleSlotConflict(SlotConflictException exception) {
        ApiResponse<?> response = new ApiResponse<>(
                false,
                exception.getMessage(),
                new com.onecare.backend.dto.response.SlotConflictResponse(
                        exception.getMessage(),
                        exception.getRequestedSlot(),
                        exception.getAlternativeSlots()));
        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    /** Overlapping doctor-schedule slots (UC4 alt flow): 400, conflicting ranges in data. */
    @ExceptionHandler(com.onecare.backend.exception.TimeSlotConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleTimeSlotConflict(
            com.onecare.backend.exception.TimeSlotConflictException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage(), exception.getConflictingSlots());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /** BR004-BR006 violation on a permission change: 422, nothing persisted (AC5). */
    @ExceptionHandler(com.onecare.backend.exception.PermissionRuleViolationException.class)
    public ResponseEntity<ApiResponse<?>> handlePermissionRuleViolation(
            com.onecare.backend.exception.PermissionRuleViolationException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());
        return new ResponseEntity<>(response, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** Restore without confirm=true (UC5, AC4): 400, no data touched. */
    @ExceptionHandler(com.onecare.backend.exception.RestoreConfirmationRequiredException.class)
    public ResponseEntity<ApiResponse<?>> handleRestoreConfirmationRequired(
            com.onecare.backend.exception.RestoreConfirmationRequiredException exception) {
        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception ex) {

        log.error("Unhandled exception handling request", ex);

        ApiResponse<?> response = new ApiResponse<>(false, "Internal Server Error");

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(InvalidStockException.class)
public ResponseEntity<ApiResponse<?>> handleInvalidStock(
        InvalidStockException exception
) {

    ApiResponse<?> response =
            new ApiResponse<>(false, exception.getMessage());

    return new ResponseEntity<>(
            response,
            HttpStatus.BAD_REQUEST
    );
}

        @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ApiResponse<?>> handleDuplicateUser(DuplicateUserException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleConflict(ConflictException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    /**
     * Optimistic-locking conflict (api-standards 942): another request modified
     * the same invoice between our read and our write - for billing this is how
     * two concurrent payments are stopped instead of overpaying the invoice.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<?>> handleOptimisticLock(OptimisticLockingFailureException exception) {

        ApiResponse<?> response = new ApiResponse<>(false,
                "The record was modified by another request. Please retry.");

        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessRule(BusinessRuleException exception) {

        ApiResponse<?> response = new ApiResponse<>(false, exception.getMessage());

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {

        ApiResponse<?> response = new ApiResponse<>(false,
                "Invalid value '" + exception.getValue()
                        + "' for parameter '" + exception.getName() + "'");

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
                            ife.getTargetType().getEnumConstants());
        }

        return new ResponseEntity<>(
                new ApiResponse<>(false, message),
                HttpStatus.BAD_REQUEST);
    }


}
