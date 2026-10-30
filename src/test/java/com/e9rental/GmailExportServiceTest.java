package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.service.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GmailExportServiceTest {
    @TempDir Path temp;
    GmailGateway gateway=mock(GmailGateway.class);
    @org.junit.jupiter.api.BeforeEach void syntheticProvider() { when(gateway.dataMode()).thenReturn("SYNTHETIC"); }
    GmailExportService service(String reference) {
        return new GmailExportService(gateway,new GmailAnalyzer(),Fixtures.JSON,Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),
            "test-tenant",reference,temp.resolve("gmail_output.json").toString());
    }
    @Test void writesCompleteSnapshotAndUsesConfiguredReference() throws Exception {
        when(gateway.read()).thenReturn(new GmailGateway.Mailbox("owner@example.test",Fixtures.threads()));
        var result=service("2026-10-14").export(null);
        assertThat(result.referenceDate()).isEqualTo(LocalDate.of(2026,10,14));
        assertThat(result.tenantId()).isEqualTo("test-tenant");
        assertThat(result.connectorAccountId()).startsWith("gmail-");
        assertThat(Fixtures.JSON.readTree(temp.resolve("gmail_output.json").toFile()).path("message_count").asInt()).isEqualTo(6);
    }
    @Test void requestReferenceOverridesEnvironmentAndDefaultUsesUtcClock() throws Exception {
        when(gateway.read()).thenReturn(new GmailGateway.Mailbox("owner@example.test",java.util.List.of()));
        assertThat(service("2026-10-01").export(LocalDate.of(2026,10,15)).referenceDate()).isEqualTo(LocalDate.of(2026,10,15));
        assertThat(service("").export(null).referenceDate()).isEqualTo(LocalDate.of(2026,10,16));
    }
    @Test void upstreamFailurePreservesPreviousExport() throws Exception {
        Files.writeString(temp.resolve("gmail_output.json"),"previous-private-export");
        when(gateway.read()).thenThrow(new GmailFailure(403,"Denied"));
        assertThatThrownBy(()->service("").export(null)).hasMessage("Denied");
        assertThat(Files.readString(temp.resolve("gmail_output.json"))).isEqualTo("previous-private-export");
    }
    @Test void malformedDateFailsBeforeCallingGoogle() {
        assertThatThrownBy(()->service("invalid").export(null)).isInstanceOf(java.time.format.DateTimeParseException.class);
        verifyNoInteractions(gateway);
    }
    @Test void boundedVerificationIsExplicitlyLabelled() throws Exception {
        when(gateway.read(1)).thenReturn(new GmailGateway.Mailbox("owner@example.test",java.util.List.of(Fixtures.threads().getFirst())));
        var output=service("").export(null,1);
        assertThat(output.coverage()).isEqualTo("LIMITED_VERIFICATION");
        assertThat(output.requestedThreadLimit()).isEqualTo(1);
        assertThat(output.messageCount()).isEqualTo(1);
        verify(gateway,never()).read();
    }
    @Test void unknownProviderModeFailsBeforeMailboxRead() {
        when(gateway.dataMode()).thenReturn(null);
        assertThatThrownBy(()->service("").export(null)).isInstanceOf(GmailFailure.class).hasMessage("Google provider mode is invalid");
        verify(gateway,never()).read();verify(gateway,never()).read(anyInt());
    }
    @Test void personalLiveOnlyExportIsRejectedBeforeProviderOrFileAccess() {
        var personal=new GoogleSandboxPolicy("PERSONAL","false","","","","true","true","owner@example.test","","","false");
        Path output=temp.resolve("must-not-be-created.json");
        var service=new GmailExportService(gateway,new GmailAnalyzer(),Fixtures.JSON,Clock.systemUTC(),
            "personal-test-tenant","",output.toString(),personal);

        assertThatThrownBy(()->service.export(null)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail export is unavailable in Live Gmail-only Personal mode");

        verifyNoInteractions(gateway);
        assertThat(Files.exists(output)).isFalse();
    }
}
