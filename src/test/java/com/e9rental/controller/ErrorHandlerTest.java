package com.e9rental.controller;

import com.e9rental.connector.GmailFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerTest {
    @Test void returnsOnlyAllowlistedDiagnosticsAndNeverRawReasonText() {
        var failure=new GmailFailure(503,"Gmail question retrieval failed","PRIVATE_MESSAGE_OR_PROVIDER_BODY_SENTINEL",
            GmailFailure.DiagnosticCategory.GMAIL_NETWORK_FAILURE);
        var response=new ErrorHandler().gmail(failure);
        String serialized=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.getBody()).toString();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(serialized).contains("GMAIL_NETWORK_FAILURE","Gmail question retrieval failed","UNSPECIFIED");
        assertThat(serialized).doesNotContain("PRIVATE_MESSAGE_OR_PROVIDER_BODY_SENTINEL");
    }

    @Test void preservesFixedRetrievalLimitCategoriesAndHttpMappings() {
        Map<GmailFailure.DiagnosticCategory,Integer> cases=Map.of(
            GmailFailure.DiagnosticCategory.GMAIL_PAGINATION_LIMIT_EXCEEDED,503,
            GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED,502,
            GmailFailure.DiagnosticCategory.GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED,503,
            GmailFailure.DiagnosticCategory.GMAIL_MIME_PART_LIMIT_EXCEEDED,503);
        for(var entry:cases.entrySet()) {
            var response=new ErrorHandler().gmail(new GmailFailure(entry.getValue(),"Gmail question retrieval failed","UNSPECIFIED",entry.getKey()));
            String serialized=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.getBody()).toString();
            assertThat(response.getStatusCode().value()).isEqualTo(entry.getValue());
            assertThat(serialized).contains(entry.getKey().name()).doesNotContain("messageId","PRIVATE");
        }
    }

    @Test void paginationErrorGivesFixedNarrowingGuidanceWithoutProviderDetails() {
        var failure=new GmailFailure(503,"Gmail question retrieval failed","PRIVATE_QUERY_OR_RESPONSE_SENTINEL",
            GmailFailure.DiagnosticCategory.GMAIL_PAGINATION_LIMIT_EXCEEDED);
        var response=new ErrorHandler().gmail(failure);
        String serialized=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.getBody()).toString();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(serialized).contains("GMAIL_PAGINATION_LIMIT_EXCEEDED","three specific words","upstream_reason","UNSPECIFIED");
        assertThat(serialized).doesNotContain("PRIVATE_QUERY_OR_RESPONSE_SENTINEL","Gmail question retrieval failed");
    }

    @Test void viewerTimeoutAndOverloadResponsesAreFixedAndContentFree() {
        for(var category:java.util.List.of(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT,
                GmailFailure.DiagnosticCategory.GMAIL_VIEWER_CAPACITY_EXHAUSTED)) {
            var response=new ErrorHandler().gmail(new GmailFailure(503,"PRIVATE_BODY_SENTINEL","PRIVATE_PROVIDER_REASON",category));
            String serialized=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.getBody()).toString();
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(serialized).contains(category.name(),"upstream_reason","UNSPECIFIED")
                .doesNotContain("PRIVATE_BODY_SENTINEL","PRIVATE_PROVIDER_REASON","messageId");
        }
        var askTimeout=new ErrorHandler().gmail(new GmailFailure(503,"Gmail question retrieval time limit exceeded","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT));
        String askJson=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(askTimeout.getBody()).toString();
        assertThat(askJson).contains("Gmail request timed out before completion").doesNotContain("message request","PRIVATE");
    }
}
