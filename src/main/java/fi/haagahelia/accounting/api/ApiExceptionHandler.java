package fi.haagahelia.accounting.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ApiError;

@RestControllerAdvice(basePackages = "fi.haagahelia.accounting.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException exception) {

        int status = exception.getStatusCode().value();
        String message = exception.getReason() != null
            ? exception.getReason()
            : "Request failed";

        return ResponseEntity
            .status(exception.getStatusCode())
            .body(new ApiError(status, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidBody(
            HttpMessageNotReadableException exception) {

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(
                400,
                "Invalid request body"
            ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(
            MethodArgumentTypeMismatchException exception) {

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(
                400,
                "Invalid value for parameter: " + exception.getName()
            ));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingConflict(
            OptimisticLockingFailureException exception) {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(new ApiError(
                409,
                "The account was updated by another request. "
                    + "Refresh the data and try again."
            ));
    }
}