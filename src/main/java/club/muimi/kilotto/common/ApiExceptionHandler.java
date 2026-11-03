package club.muimi.kilotto.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException error) {
        return ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason() == null ? "请求失败" : error.getReason());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail invalid(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof club.muimi.kilotto.auth.security.RequestGuardFilter.PayloadTooLargeException) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "请求内容超过 16 KiB 限制");
            }
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "输入格式不正确，请检查填写内容");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据冲突，用户名可能已存在");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账户已被禁用");
    }
}
