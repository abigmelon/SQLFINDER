package com.sqlfinder.common.api;

import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.common.application.QuestionProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

/**
 * 全局异常处理
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TableAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleTableAccessDenied(TableAccessDeniedException ex) {
        return failure(HttpStatus.FORBIDDEN, "TABLE_ACCESS_DENIED", ex.getMessage(), ex);
    }

    @ExceptionHandler(QuestionProcessingException.class)
    public ResponseEntity<ApiErrorResponse> handleQuestionProcessing(
            QuestionProcessingException ex) {

        return switch (ex.getStage()) {
            case METADATA -> failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METADATA_UNAVAILABLE",
                    "暂时无法读取授权表结构，请稍后重试。",
                    ex);

            case PROMPT -> failure(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PROMPT_BUILD_FAILED",
                    "生成模型输入失败。",
                    ex);

            case MODEL_REQUEST -> failure(
                    HttpStatus.BAD_GATEWAY,
                    "MODEL_REQUEST_FAILED",
                    "模型请求失败，请稍后重试。",
                    ex);

            case MODEL_OUTPUT -> failure(
                    HttpStatus.BAD_GATEWAY,
                    "MODEL_OUTPUT_INVALID",
                    "模型返回的内容格式不符合要求。",
                    ex);
        };
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        String code;
        String message;

        if (ex instanceof MethodArgumentNotValidException) {
            code = "INVALID_ARGUMENT";
            message = "请求参数校验失败，请检查必填项。";
        } else if (ex instanceof HttpMessageNotReadableException) {
            code = "INVALID_JSON";
            message = "请求体缺失或 JSON 格式、字段类型不正确。";
        } else {
            code = switch (statusCode.value()) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "HTTP_" + statusCode.value();
            };

            message = switch (statusCode.value()) {
                case 400 -> "请求参数不正确。";
                case 404 -> "请求的资源不存在。";
                case 405 -> "该接口不支持当前请求方法。";
                case 406 -> "无法提供请求所需的响应格式。";
                case 415 -> "请求的内容类型不受支持。";
                default -> "请求处理失败。";
            };
        }

        ApiErrorResponse error =
                newError(code, message, ex, statusCode);

        HttpHeaders errorHeaders = new HttpHeaders();
        errorHeaders.putAll(headers);
        errorHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(
                ex, error, errorHeaders, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        return failure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务内部错误，请稍后重试。",
                ex);
    }

    private ResponseEntity<ApiErrorResponse> failure(
            HttpStatus status,
            String code,
            String message,
            Exception ex) {

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(newError(code, message, ex, status));
    }

    private ApiErrorResponse newError(
            String code,
            String message,
            Exception ex,
            HttpStatusCode status) {

        String requestId = UUID.randomUUID().toString();

        if (status.is5xxServerError()) {
            Throwable cause =
                    ex.getCause() == null ? ex : ex.getCause();

            log.error(
                    "requestId={} errorCode={} exceptionType={}",
                    requestId,
                    code,
                    cause.getClass().getName());
        }

        return new ApiErrorResponse(code, message, requestId);
    }
}
