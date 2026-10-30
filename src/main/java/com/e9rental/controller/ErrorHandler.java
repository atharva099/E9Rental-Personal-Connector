package com.e9rental.controller;
import com.e9rental.connector.GmailFailure;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType(Exception e) { return ResponseEntity.status(415).body(Map.of("error","Unsupported content type")); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception e) { return ResponseEntity.status(405).body(Map.of("error","Unsupported request method")); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("error","Endpoint not found")); }
    @ExceptionHandler(GmailFailure.class)
    ResponseEntity<?> gmail(GmailFailure e) {
        if(com.e9rental.config.GoogleSandboxPolicy.DENIAL.equals(e.reason()))
            return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage()));
        int http = e.status() == 400 ? 400 : e.status() == 401 ? 401 : e.status() == 413 ? 413 : e.status() == 503 ? 503 : 502;
        Map<String,Object> body=new java.util.LinkedHashMap<>();
        String safeMessage=e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_PAGINATION_LIMIT_EXCEEDED
            ? "Gmail search found more results than this bounded request can inspect. Narrow the topic with up to three specific words and try again."
            : e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_HISTORY_CURSOR_EXPIRED
            ? "Gmail sync history expired. No changes were committed; request a reviewed recovery action rather than a full resynchronization."
            : e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT
            ? "The Gmail request timed out before completion. No result was returned."
            : e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_VIEWER_CAPACITY_EXHAUSTED
            ? "The Gmail viewer is busy; try again later."
            : e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT
            ? "Live Gmail search timed out before completion. No results were returned."
            : e.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_SEARCH_CAPACITY_EXHAUSTED
            ? "Live Gmail search is busy; try again later."
            : e.getMessage();
        body.put("error",safeMessage);body.put("upstream_status",e.status());body.put("upstream_reason",e.safeReason());
        if(e.diagnosticCategory()!=GmailFailure.DiagnosticCategory.NONE)
            body.put("error_category",e.diagnosticCategory().name());
        return ResponseEntity.status(http).body(body);
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid request or configuration")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) { return ResponseEntity.internalServerError().body(Map.of("error","Operation failed; inspect local configuration and retry")); }
}
