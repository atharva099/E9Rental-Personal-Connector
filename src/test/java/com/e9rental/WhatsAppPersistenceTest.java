package com.e9rental;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.WhatsAppMessageStore;
import com.e9rental.service.WhatsAppIngestionService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class WhatsAppPersistenceTest {
    @TempDir Path temp;
    @Test void persistsAcrossRepositoryRecreationAndRejectsDuplicateOrConflictingReplay() throws Exception {
        String url="jdbc:h2:file:"+temp.resolve("accepted").toString().replace('\\','/');
        var first=new WhatsAppMessageStore(url,Fixtures.JSON);
        var event=WhatsAppFixtures.event("sim-m","sim-group-rentals","I will send the synthetic quotation.");
        var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),first,WhatsAppFixtures.CLOCK);
        assertThat(service.ingest(event).status()).isEqualTo("ACCEPTED");
        var reopened=new WhatsAppMessageStore(url,Fixtures.JSON);var other=new WhatsAppIngestionService(WhatsAppFixtures.policy(),reopened,WhatsAppFixtures.CLOCK);
        assertThat(other.ingest(event).status()).isEqualTo("DUPLICATE");
        event.put("content","CONFLICT_SENTINEL");
        assertThat(other.ingest(event).status()).isEqualTo("CONFLICT");
        assertThat(reopened.selected("test-tenant","sim-account-test",WhatsAppFixtures.policy().selectedGroups())).hasSize(1).allSatisfy(m->assertThat(m.content()).doesNotContain("CONFLICT_SENTINEL"));
        try(var files=Files.list(temp)){assertThat(files.map(p->p.getFileName().toString()).toList()).noneMatch(n->n.endsWith(".trace.db"));}
    }
    @Test void concurrentDuplicatesYieldOneAcceptedMessage() throws Exception {
        var store=WhatsAppFixtures.store();var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        var event=WhatsAppFixtures.event("sim-concurrent","sim-group-rentals","Synthetic concurrency");
        try(var pool=Executors.newFixedThreadPool(4)){
            List<Callable<String>> tasks=new ArrayList<>();for(int i=0;i<12;i++)tasks.add(()->service.ingest(event).status());
            var results=pool.invokeAll(tasks).stream().map(f->{try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).toList();
            assertThat(results.stream().filter("ACCEPTED"::equals).count()).isEqualTo(1);assertThat(results.stream().filter("DUPLICATE"::equals).count()).isEqualTo(11);
        }
    }
    @Test void scopesIdentityAndReadsByTenantAccountAndSelectedGroup() {
        var store=WhatsAppFixtures.store();
        var a=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        assertThat(a.ingest(WhatsAppFixtures.event("sim-shared","sim-group-rentals","Tenant one")).status()).isEqualTo("ACCEPTED");
        assertThat(a.ingest(WhatsAppFixtures.event("sim-shared","sim-group-operations","Other group")).status()).isEqualTo("ACCEPTED");
        var policy=new WhatsAppPolicy("SIMULATED",WhatsAppFixtures.GROUPS,"other-tenant","sim-account-test");
        assertThat(new WhatsAppIngestionService(policy,store,WhatsAppFixtures.CLOCK).ingest(WhatsAppFixtures.event("sim-shared","sim-group-rentals","Tenant two")).status()).isEqualTo("ACCEPTED");
        assertThat(store.selected("test-tenant","sim-account-test",Set.of("sim-group-rentals"))).hasSize(1).allSatisfy(m->assertThat(m.content()).isEqualTo("Tenant one"));
        assertThat(store.selected("test-tenant","sim-different",WhatsAppFixtures.policy().selectedGroups())).isEmpty();
    }
    @Test void storesSqlLikeContentAsLiteralDataAndNeverStoresRejectedSentinel() throws Exception {
        var store=WhatsAppFixtures.store();var service=new WhatsAppIngestionService(WhatsAppFixtures.policy(),store,WhatsAppFixtures.CLOCK);
        String literal="Synthetic '; DROP TABLE simulated_whatsapp_messages;--";
        assertThat(service.ingest(WhatsAppFixtures.event("sim-sql","sim-group-rentals",literal)).status()).isEqualTo("ACCEPTED");
        assertThat(service.ingest(WhatsAppFixtures.event("sim-reject","sim-group-private","EXCLUDED_SENTINEL")).status()).isEqualTo("REJECTED");
        var rows=store.selected("test-tenant","sim-account-test",WhatsAppFixtures.policy().selectedGroups());
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().content()).isEqualTo(literal);
        assertThat(Fixtures.JSON.writeValueAsString(rows)).doesNotContain("EXCLUDED_SENTINEL");
    }
}
