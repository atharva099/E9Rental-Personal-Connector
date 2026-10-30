package com.e9rental;
import com.e9rental.connector.GmailFailure;
import com.e9rental.model.GmailOutput;
import com.e9rental.service.GmailAnalyzer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
class GmailAnalyzerTest {
    final GmailAnalyzer analyzer=new GmailAnalyzer();
    GmailOutput analyze(JsonNode... threads) {
        return analyzer.analyze(List.of(threads),"owner@example.test","tenant-a","account-a",LocalDate.of(2026,10,16),Fixtures.NOW,"SYNTHETIC");
    }
    @Test void classifiesSalesMarketingAndOtherWithReasons() throws Exception {
        var output=analyze(Fixtures.threads().toArray(JsonNode[]::new));
        assertThat(output.messages().get(0).classification()).isEqualTo("sales");
        assertThat(output.messages().get(1).classification()).isEqualTo("marketing");
        assertThat(output.messages().stream().filter(m->m.source().externalId().equals("m3")).findFirst().orElseThrow().classification()).isEqualTo("other");
        assertThat(output.messages()).allSatisfy(m->assertThat(m.classificationReasons()).isNotEmpty());
    }
    @Test void marketingSignalsTakePrecedenceOverSalesWords() {
        assertThat(analyzer.classify("Invoice discount","", "",List.of()).name()).isEqualTo("marketing");
        assertThat(analyzer.classify("Invoice","", "",List.of("CATEGORY_PROMOTIONS")).name()).isEqualTo("marketing");
        assertThat(analyzer.classify("Hello","", "<mailto:x@example.test>",List.of()).name()).isEqualTo("marketing");
    }
    @Test void matchesWholeWordsRatherThanSubstring() {
        assertThat(analyzer.classify("Invoiceless report","", "",List.of()).name()).isEqualTo("other");
    }
    @Test void flagsOnlyOldUnansweredInboundMessages() throws Exception {
        var output=analyze(Fixtures.threads().toArray(JsonNode[]::new));
        assertThat(output.messages().stream().filter(m->m.unansweredFiveDays()).map(m->m.source().externalId())).containsExactly("m1","m2");
    }
    @Test void fiveDayBoundaryIsInclusive() {
        var m=Fixtures.message("a","2026-10-11T00:00:00Z","INBOX");
        assertThat(analyze(Fixtures.thread(m)).messages().getFirst().unansweredFiveDays()).isTrue();
        m.put("internalDate",String.valueOf(Instant.parse("2026-10-11T00:00:00.001Z").toEpochMilli()));
        assertThat(analyze(Fixtures.thread(m)).messages().getFirst().unansweredFiveDays()).isFalse();
    }
    @Test void replyBeforeInboundDoesNotCountAndOrderDoesNotMatter() {
        var old=Fixtures.message("s","2026-10-01T00:00:00Z","SENT");
        var inbound=Fixtures.message("i","2026-10-02T00:00:00Z","INBOX");
        var later=Fixtures.message("r","2026-10-03T00:00:00Z","SENT");
        assertThat(analyze(Fixtures.thread(inbound,old)).messages().getFirst().unansweredFiveDays()).isTrue();
        assertThat(analyze(Fixtures.thread(later,inbound,old)).messages().get(1).unansweredFiveDays()).isFalse();
    }
    @Test void replyAfterReferenceDateDoesNotAlterHistoricalAnswer() {
        var inbound=Fixtures.message("i","2026-10-02T00:00:00Z","INBOX");
        var future=Fixtures.message("s","2026-10-17T00:00:00Z","SENT");
        assertThat(analyze(Fixtures.thread(inbound,future)).messages().getFirst().unansweredFiveDays()).isTrue();
    }
    @Test void draftSpamTrashAndOwnMessagesAreNotInbound() {
        for (String label:List.of("DRAFT","SPAM","TRASH","SENT")) {
            assertThat(analyze(Fixtures.thread(Fixtures.message("m","2026-10-01T00:00:00Z",label))).messages().getFirst().inbound()).isFalse();
        }
        var own=Fixtures.message("m","2026-10-01T00:00:00Z","INBOX");
        ((ObjectNode)own.path("payload").path("headers").get(1)).put("value","Owner <owner@example.test>");
        assertThat(analyze(Fixtures.thread(own)).messages().getFirst().inbound()).isFalse();
    }
    @Test void identifiesAllFiveCriticalAttachmentTypesWithReasons() throws Exception {
        var attachments=analyze(Fixtures.threads().getFirst()).messages().getFirst().attachments();
        assertThat(attachments).hasSize(6);
        assertThat(attachments.subList(0,5)).allSatisfy(a->{ assertThat(a.businessCritical()).isTrue(); assertThat(a.reasons()).isNotEmpty(); assertThat(a.attachmentId()).startsWith("att-"); });
        assertThat(attachments.getLast().businessCritical()).isFalse();
    }
    @Test void mapsStableScopedIdentityAndProvenance() throws Exception {
        var thread=Fixtures.threads().getFirst();
        var source=analyze(thread).messages().getFirst().source();
        assertThat(source.sourceType()).isEqualTo("GMAIL");
        assertThat(source.parentExternalId()).isEqualTo("t1");
        assertThat(source.contentText()).isEqualTo("Please share pricing");
        assertThat(source.contentHash()).hasSize(64);
        assertThat(source.sourceCreatedAt()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        assertThat(source.sourceUpdatedAt()).isNull();
        assertThat(source.documentId()).isEqualTo(analyze(thread).messages().getFirst().source().documentId());
        var other=analyzer.analyze(List.of(thread),"owner@example.test","other-tenant","account-a",LocalDate.of(2026,10,16),Fixtures.NOW,"SYNTHETIC");
        assertThat(other.messages().getFirst().source().documentId()).isNotEqualTo(source.documentId());
        var citation=analyze(thread).messages().getFirst().citation();
        assertThat(citation.documentId()).isEqualTo(source.documentId());
        assertThat(citation.sourceVersion()).isEqualTo(source.sourceVersion());
        assertThat(citation.sourceUrl()).doesNotContain("token");
    }
    @Test void emptyMailboxAndDuplicateMessageAreSafe() throws Exception {
        assertThat(analyze().messages()).isEmpty();
        var t=Fixtures.threads().getFirst();
        assertThat(analyze(t,t).messageCount()).isEqualTo(1);
    }
    @Test void missingDatesAreNotInvented() {
        var m=Fixtures.message("m","2026-10-01T00:00:00Z"); m.remove("internalDate");
        var result=analyze(Fixtures.thread(m)).messages().getFirst();
        assertThat(result.source().occurredAt()).isNull(); assertThat(result.unansweredFiveDays()).isFalse();
    }
    @Test void invalidIdentityOrTimestampFailsRatherThanSilentlyLosingRecords() {
        var m=Fixtures.message("m","2026-10-01T00:00:00Z"); m.put("internalDate","invalid");
        assertThatThrownBy(()->analyze(Fixtures.thread(m))).isInstanceOf(GmailFailure.class);
        m.remove("id");
        assertThatThrownBy(()->analyze(Fixtures.thread(m))).isInstanceOf(GmailFailure.class);
        assertThatThrownBy(()->analyze(Fixtures.JSON.createObjectNode())).isInstanceOf(GmailFailure.class);
    }
    @Test void nestedAlternativePrefersPlainAndDoesNotIncludeAttachmentContent() {
        var m=Fixtures.message("m","2026-10-01T00:00:00Z");
        var payload=(ObjectNode)m.path("payload"); payload.put("mimeType","multipart/alternative"); payload.remove("body");
        var parts=payload.putArray("parts");
        parts.addObject().put("mimeType","text/html").putObject("body").put("data",Base64.getUrlEncoder().encodeToString("<p>duplicate</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        parts.addObject().put("mimeType","text/plain").putObject("body").put("data",Base64.getUrlEncoder().encodeToString("plain".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        parts.addObject().put("mimeType","text/plain").put("filename","secret.txt").putObject("body").put("data","aGlkZGVu");
        assertThat(analyze(Fixtures.thread(m)).messages().getFirst().source().contentText()).isEqualTo("plain");
    }
    @Test void generatesExplicitlySyntheticPracticeOutput() throws Exception {
        var output=analyze(Fixtures.threads().toArray(JsonNode[]::new));
        Path path=Path.of(System.getProperty("practice.synthetic.output","target/synthetic/gmail_output.json")); Files.createDirectories(path.getParent());
        Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),output);
        var actual=Fixtures.JSON.readTree(path.toFile());
        assertThat(actual.path("data_mode").asText()).isEqualTo("SYNTHETIC");
        assertThat(actual.path("message_count").asInt()).isEqualTo(6);
        assertThat(actual.path("messages").get(0).path("source").has("document_id")).isTrue();
    }
}
