package com.e9rental;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.WhatsAppMessageStore;
import com.e9rental.service.WhatsAppIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WhatsAppIngestionTest {
    @Test void rejectsUnselectedBeforeTouchingContentOrPersistence() {
        var store=mock(WhatsAppMessageStore.class);JsonNode input=mock(JsonNode.class);
        when(input.isObject()).thenReturn(true);when(input.path("group_id")).thenReturn(TextNode.valueOf("sim-group-excluded"));
        var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        assertThat(service.ingest(input).status()).isEqualTo("REJECTED");
        verify(input,never()).path("content");verify(input,never()).fieldNames();verifyNoInteractions(store);
    }
    @Test void processesCompleteFixtureWithExactCountsAndOnlyAcceptedRows() throws Exception {
        var store=WhatsAppFixtures.store();var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        java.util.Map<String,Integer> counts=new java.util.HashMap<>();
        for(var event:WhatsAppFixtures.events())counts.merge(service.ingest(event).status(),1,Integer::sum);
        assertThat(counts).containsEntry("ACCEPTED",6).containsEntry("DUPLICATE",1).containsEntry("REJECTED",2).containsEntry("CONFLICT",1).containsEntry("INVALID",2);
        var rows=store.selected("test-tenant","sim-account-test",WhatsAppFixtures.policy().selectedGroups());
        assertThat(rows).hasSize(6);assertThat(Fixtures.JSON.writeValueAsString(rows)).doesNotContain("EXCLUDED_SENTINEL","CONFLICT_SENTINEL");
    }
    @Test void emptyAllowlistFailsClosed() {
        var store=mock(WhatsAppMessageStore.class);
        var policy=new WhatsAppPolicy("SIMULATED","","t","sim-account");
        assertThat(new WhatsAppIngestionService(policy,store,WhatsAppFixtures.CLOCK).ingest(WhatsAppFixtures.event("sim-m","sim-group-rentals","Synthetic")).status()).isEqualTo("REJECTED");
        verifyNoInteractions(store);
    }
    @Test void liveModeCannotBeEnabledAndDoesNotInspectPayload() {
        var store=mock(WhatsAppMessageStore.class);JsonNode payload=mock(JsonNode.class);
        var policy=new WhatsAppPolicy("LIVE",WhatsAppFixtures.GROUPS,"t","sim-account");
        assertThatThrownBy(()->new WhatsAppIngestionService(policy,store,WhatsAppFixtures.CLOCK).ingest(payload)).hasMessageContaining("Only simulated");
        verifyNoInteractions(store,payload);
    }
    @Test void validatesFieldsBeforePersistence() {
        var store=mock(WhatsAppMessageStore.class);var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        for(String field:java.util.List.of("sender_id","message_id","timestamp","content","data_mode")){
            var input=WhatsAppFixtures.event("sim-valid","sim-group-rentals","Synthetic");input.remove(field);
            assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        }
        var input=WhatsAppFixtures.event("sim-valid","sim-group-rentals","Synthetic");input.put("tenant_id","attacker");
        assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        input.remove("tenant_id");input.put("data_mode","LIVE");assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        verifyNoInteractions(store);
    }
    @Test void rejectsUnsafeIdsAndEmptyOverlongOrControlCharacterContent() {
        var store=mock(WhatsAppMessageStore.class);var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        for(String body:java.util.List.of(" ","x".repeat(4097),"a\u0000b","a"+(char)0xD800)){
            assertThat(service.ingest(WhatsAppFixtures.event("sim-valid","sim-group-rentals",body)).status()).isEqualTo("INVALID");
        }
        var input=WhatsAppFixtures.event("sim-valid","sim-group-rentals","Synthetic");
        input.put("message_id","sim-id'); DROP TABLE test;--");assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        input.put("message_id","sim-valid");input.put("sender_id","sim-user\nFORGED_LOG");assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        verifyNoInteractions(store);
    }
    @Test void rejectsInvalidPastAndFutureTimestamps() {
        var store=mock(WhatsAppMessageStore.class);var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        for(String time:java.util.List.of("invalid","1999-12-31T00:00:00Z","2026-10-17T12:05:01Z")){
            var input=WhatsAppFixtures.event("sim-valid","sim-group-rentals","Synthetic");input.put("timestamp",time);
            assertThat(service.ingest(input).status()).isEqualTo("INVALID");
        }
        verifyNoInteractions(store);
    }
    @Test void validatesEnvelopeAndSyntheticConfiguration() {
        var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),mock(WhatsAppMessageStore.class),WhatsAppFixtures.CLOCK);
        assertThat(service.ingest(null).status()).isEqualTo("INVALID");
        assertThat(service.ingest(Fixtures.JSON.createArrayNode()).status()).isEqualTo("INVALID");
        assertThatThrownBy(()->new WhatsAppPolicy("SIMULATED","real-group","t","sim-account")).isInstanceOf(IllegalArgumentException.class);
    }
}
