package com.e9rental;

import com.e9rental.service.GmailMessageBodyExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GmailMessageBodyExtractorTest {
    private final GmailMessageBodyExtractor extractor=new GmailMessageBodyExtractor();
    private JsonNode json(String value)throws Exception{return Fixtures.JSON.readTree(value);}
    private static String encoded(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}

    @Test void plainTextPreservesReadableFormatting() throws Exception {
        var result=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded("First line\nSecond line")+"\"}}"));
        assertThat(result.text()).isEqualTo("First line\nSecond line");assertThat(result.available()).isTrue();
        assertThat(result.incomplete()).isFalse();assertThat(result.truncated()).isFalse();
    }
    @Test void multipartAlternativePrefersPlainText() throws Exception {
        var result=extractor.extract(json("{\"mimeType\":\"multipart/alternative\",\"parts\":["+
            "{\"mimeType\":\"text/html\",\"body\":{\"data\":\""+encoded("<p>HTML version</p>")+"\"}},"+
            "{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded("Plain version")+"\"}}]}"));
        assertThat(result.text()).isEqualTo("Plain version");assertThat(result.incomplete()).isFalse();
    }
    @Test void htmlOnlyBodyBecomesInertReadableTextWithoutScriptOrRemoteResource() throws Exception {
        String html="<html><head><title>hidden title</title></head><body><p>Hello &amp; welcome</p>"+
            "<script>privateCall()</script><style>.x{display:none}</style><img src='https://tracking.invalid/pixel'>"+
            "<p>Second paragraph</p></body></html>";
        var result=extractor.extract(json("{\"mimeType\":\"text/html\",\"body\":{\"data\":\""+encoded(html)+"\"}}"));
        assertThat(result.available()).isTrue();assertThat(result.text()).contains("Hello & welcome","Second paragraph")
            .doesNotContain("privateCall","tracking.invalid","<script>","display:none","<p>");
    }
    @Test void textPlainContainingHtmlMarkupIsConvertedToReadableText() throws Exception {
        String html="<html><body><p>Invoice &amp; receipt</p><script>privateCall()</script>"+
            "<style>.secret { display:none }</style><img src=\"https://tracking.invalid/pixel\">"+
            "<p>Second paragraph"; // Deliberately malformed closing markup.
        var result=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded(html)+"\"}}"));
        assertThat(result.available()).isTrue();
        assertThat(result.text()).contains("Invoice & receipt","Second paragraph").doesNotContain(
            "<html>","<p>","privateCall","display:none","tracking.invalid","https://");
    }
    @Test void readableTextPlainIsPreserved() throws Exception {
        String plain="Invoice update\nPlease review the attached document.";
        var result=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded(plain)+"\"}}"));
        assertThat(result.text()).isEqualTo(plain);assertThat(result.available()).isTrue();
    }
    @Test void ordinaryAngleBracketTextIsNotMistakenForHtml() throws Exception {
        String plain="Use x < y and y > z; the value <invoice> is a placeholder.";
        var result=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded(plain)+"\"}}"));
        assertThat(result.text()).isEqualTo(plain);assertThat(result.available()).isTrue();
    }
    @Test void oversizedBodyIsExplicitlyTruncated() throws Exception {
        String source="x".repeat(GmailMessageBodyExtractor.MAX_BODY_CHARS+100);
        var result=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded(source)+"\"}}"));
        assertThat(result.available()).isTrue();assertThat(result.truncated()).isTrue();assertThat(result.incomplete()).isTrue();
        assertThat(result.text()).hasSize(GmailMessageBodyExtractor.MAX_BODY_CHARS);
    }
    @Test void missingMalformedAndExternalizedBodiesAreUnavailableOrIncomplete() throws Exception {
        var missing=extractor.extract(json("{\"mimeType\":\"multipart/mixed\",\"parts\":[]}"));
        var malformed=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"%%%\"}}"));
        var externalized=extractor.extract(json("{\"mimeType\":\"text/plain\",\"body\":{\"attachmentId\":\"synthetic-body-id\"}}"));
        assertThat(missing.available()).isFalse();assertThat(missing.incomplete()).isTrue();
        assertThat(malformed.available()).isFalse();assertThat(malformed.incomplete()).isTrue();assertThat(malformed.text()).isEmpty();
        assertThat(externalized.available()).isFalse();assertThat(externalized.incomplete()).isTrue();
    }
    @Test void attachmentDisplayNamesOnlyAreReturned() throws Exception {
        var result=extractor.extract(json("{\"mimeType\":\"multipart/mixed\",\"parts\":["+
            "{\"mimeType\":\"application/pdf\",\"filename\":\"..\\\\private\\\\invoice.pdf\",\"body\":{\"attachmentId\":\"synthetic-attachment-id\",\"data\":\"SECRET\"}},"+
            "{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded("Message")+"\"}}]}"));
        assertThat(result.attachmentNames()).containsExactly("invoice.pdf");assertThat(result.text()).isEqualTo("Message");
        assertThat(result.toString()).doesNotContain("synthetic-attachment-id","SECRET");
    }
    @Test void mimeTraversalLimitIsExplicitlyIncomplete() throws Exception {
        StringBuilder parts=new StringBuilder("{\"mimeType\":\"multipart/mixed\",\"parts\":[");
        for(int i=0;i<GmailMessageBodyExtractor.MAX_MIME_PARTS+2;i++){if(i>0)parts.append(',');parts.append("{\"mimeType\":\"application/octet-stream\"}");}
        parts.append("]}");var result=extractor.extract(json(parts.toString()));
        assertThat(result.incomplete()).isTrue();assertThat(result.truncated()).isTrue();
    }
}
