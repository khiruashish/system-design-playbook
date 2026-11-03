package com.sdp.ci.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Small helpers for RFC 9457 ProblemDetail errors (spring.mvc.problemdetails.enabled=true). */
public final class Problems {
    private Problems() {}

    public static ErrorResponseException of(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
