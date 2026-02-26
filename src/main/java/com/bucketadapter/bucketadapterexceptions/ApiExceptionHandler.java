package com.bucketadapter.bucketadapterexceptions;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.services.s3.model.S3Exception;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(InvalidBucketPathException.class)
  public ResponseEntity<ProblemDetail> handleInvalidPath(HttpServletRequest req) {

    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setTitle("Invalid request");
    pd.setDetail("The provided path is invalid.");
    pd.setProperty("path", req.getRequestURI());
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(BucketObjectNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(HttpServletRequest req) {

    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    pd.setTitle("Not found");
    pd.setDetail("Resource not found.");
    pd.setProperty("path", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
  }

  @ExceptionHandler(BucketOperationException.class)
  public ResponseEntity<ProblemDetail> handleOperation(
      BucketOperationException ex, HttpServletRequest req) {

    HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
    String detail = "Unable to process the request at the moment.";

    Throwable cause = ex.getCause();
    if (cause instanceof S3Exception s3) {
      int sc = s3.statusCode();

      if (sc == 400) status = HttpStatus.BAD_REQUEST;
      else if (sc == 401) status = HttpStatus.UNAUTHORIZED;
      else if (sc == 403) status = HttpStatus.FORBIDDEN;
      else if (sc == 404) status = HttpStatus.NOT_FOUND;
      else if (sc == 409) status = HttpStatus.CONFLICT;
      else if (sc == 429) status = HttpStatus.TOO_MANY_REQUESTS;
      else if (sc >= 500) status = HttpStatus.BAD_GATEWAY; // upstream/provider down
    }

    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setTitle("Operation failed");
    pd.setDetail(detail);
    pd.setProperty("path", req.getRequestURI());

    return ResponseEntity.status(status).body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleAny(HttpServletRequest req) {

    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    pd.setTitle("Service unavailable");
    pd.setDetail("Unable to process the request at the moment.");
    pd.setProperty("path", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
  }
}
