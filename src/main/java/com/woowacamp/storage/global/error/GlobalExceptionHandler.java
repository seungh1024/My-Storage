package com.woowacamp.storage.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.woowacamp.storage.global.response.ErrorResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
		ErrorResponse errorResponse = ErrorResponse.of(e.getHttpStatus(), e.getMessage());
		if (e.getDebugMessage() != null) {
			log.debug(e.getDebugMessage());
		}
		return ResponseEntity.status(e.getHttpStatus()).body(errorResponse);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
		CustomException customException = ErrorCode.API_NOT_FOUND.baseException();
		ErrorResponse errorResponse = ErrorResponse.of(customException.getHttpStatus(), customException.getMessage());
		return ResponseEntity.status(errorResponse.httpStatus()).body(errorResponse);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
		MissingServletRequestParameterException e) {
		log.error("[Exception] exception info = {}, exception message = {}", e.getClass(), e.getMessage());
		CustomException customException = ErrorCode.MISSING_REQUIRED_PARAMETER.baseException();
		ErrorResponse errorResponse = ErrorResponse.of(customException.getHttpStatus(), customException.getMessage());
		return ResponseEntity.status(errorResponse.httpStatus()).body(errorResponse);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
		log.error("[Exception] exception info = {}, exception message = {}", e.getClass(), e.getMessage());
		CustomException customException = ErrorCode.INVALID_ARGUMENT_ERROR.baseException();
		BindingResult bindingResult = new BeanPropertyBindingResult(e, "constraintViolation");
		for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
			String errorFieldInfo = violation.getPropertyPath().toString();
			String[] methodAndField = errorFieldInfo.split("\\.");
			String fieldName = methodAndField[1];
			String message = violation.getMessage();
			bindingResult.addError(new FieldError("constraintViolation", fieldName, message));
		}

		ErrorResponse errorResponse = ErrorResponse.of(customException.getHttpStatus(), customException.getMessage(),
			bindingResult);
		return ResponseEntity.status(errorResponse.httpStatus()).body(errorResponse);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(MethodArgumentNotValidException e) {
		CustomException customException = ErrorCode.INVALID_ARGUMENT_ERROR.baseException();
		ErrorResponse errorResponse = ErrorResponse.of(customException.getHttpStatus(), customException.getMessage(),
			e.getBindingResult());

		return ResponseEntity.status(customException.getHttpStatus()).body(errorResponse);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception e) {
		log.error(INTERNAL_SERVER_ERROR, e);
		ErrorResponse errorResponse = ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
	}

	@ExceptionHandler(NoSuchKeyException.class)
	public ResponseEntity<ErrorResponse> handleNoSuchKeyException(NoSuchKeyException e) {
		CustomException customException = ErrorCode.FILE_NOT_FOUND.baseException();
		if (e.getMessage() != null) {
			log.debug(e.getMessage());
		}
		ErrorResponse errorResponse = ErrorResponse.of(customException.getHttpStatus(), customException.getMessage());

		return ResponseEntity.status(customException.getHttpStatus()).body(errorResponse);
	}
}
