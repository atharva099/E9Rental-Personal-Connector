package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DriveExportServiceTest {
    @TempDir Path temp;DriveGateway gateway=mock(DriveGateway.class);
    @org.junit.jupiter.api.BeforeEach void syntheticProvider() { when(gateway.dataMode()).thenReturn("SYNTHETIC"); }
    DriveExportService service() {return new DriveExportService(gateway,new DriveExtractor(),new DriveClassifier(),Fixtures.JSON,Clock.fixed(Fixtures.NOW,ZoneOffset.UTC),"test-tenant",temp.resolve("drive.json").toString());}
    @Test void mapsAndClassifiesWithStableProvenanceAndPrivateOutput() throws Exception {
        var file=DriveFixtures.file("f1",DriveExtractor.DOCS,"Invoice");
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("synthetic-account",List.of(file),false));
        when(gateway.content(file)).thenReturn("Invoice payment received".getBytes(StandardCharsets.UTF_8));
        var output=service().export(5);var document=output.documents().getFirst();var source=document.source();
        assertThat(output.fileCount()).isEqualTo(1);assertThat(output.extractedCount()).isEqualTo(1);
        assertThat(document.category()).isEqualTo("accounts");assertThat(source.sourceType()).isEqualTo("DRIVE");
        assertThat(source.externalId()).isEqualTo("f1");assertThat(source.sourceVersion()).isEqualTo("7");
        assertThat(source.parentExternalId()).isEqualTo("synthetic-folder");assertThat(source.sourceUpdatedAt()).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"));
        assertThat(source.contentHash()).isEqualTo(GmailAnalyzer.hash(source.contentText()));
        assertThat(document.citation().documentId()).isEqualTo(source.documentId());
        assertThat(service().export(5).documents().getFirst().source().documentId()).isEqualTo(source.documentId());
        assertThat(Fixtures.JSON.readTree(temp.resolve("drive.json").toFile()).path("coverage").asText()).isEqualTo("LIMITED_VERIFICATION");
    }
    @Test void extractsLegacyWordThroughDriveExportWithSyntheticCitation() throws Exception {
        var file=DriveFixtures.file("legacy-word", "application/msword", "Synthetic rental quotation");
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("synthetic-account",List.of(file),false));
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/fixtures/synthetic-legacy-word.doc")) {
            assertThat(input).isNotNull();
            bytes=input.readAllBytes();
        }
        when(gateway.content(file)).thenReturn(bytes);
        var output=service().export(5);var document=output.documents().getFirst();var source=document.source();
        assertThat(output.dataMode()).isEqualTo("SYNTHETIC");
        assertThat(output.fileCount()).isEqualTo(1);assertThat(output.extractedCount()).isEqualTo(1);
        assertThat(document.extractionStatus()).isEqualTo("EXTRACTED");
        assertThat(source.contentText()).contains("Synthetic legacy Word rental quotation","USD 875");
        assertThat(document.category()).isIn("sales","marketing","verification","office","accounts");
        assertThat(document.classificationReason()).isNotBlank();
        assertThat(document.citation().sourceType()).isEqualTo("DRIVE");
        assertThat(document.citation().documentId()).isEqualTo(source.documentId());
        assertThat(document.citation().sourceVersion()).isEqualTo(source.sourceVersion());
        assertThat(document.citation().locator()).isEqualTo("drive:file:legacy-word");
    }
    @Test void honorsDownloadRestrictionsAndSizeLimit() {
        var restricted=DriveFixtures.file("a",DriveExtractor.DOCS,"KYC");restricted.withObject("/capabilities").put("canDownload",false);
        var large=DriveFixtures.file("b","application/pdf","Invoice");large.put("size",30L*1024*1024);
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("account",List.of(restricted,large),false));
        var output=service().export(5);
        assertThat(output.extractedCount()).isZero();assertThat(output.documents()).allSatisfy(d->assertThat(d.extractionStatus()).isEqualTo("SKIPPED"));
        verify(gateway,never()).content(any());
    }
    @Test void unavailableOrCorruptFilesAreExplicitAndStillHaveExactlyOneCategory() {
        var file=DriveFixtures.file("f",DriveExtractor.DOCX,"Office");
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("account",List.of(file),true));
        when(gateway.content(file)).thenReturn(new byte[]{1,2,3});
        var output=service().export(5);
        assertThat(output.documents().getFirst().extractionStatus()).isEqualTo("FAILED");
        assertThat(output.documents().getFirst().category()).isEqualTo("office");
        assertThat(output.incompleteSearch()).isTrue();
    }
    @Test void emptyDriveProducesValidEmptyExport() {
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("account",List.of(),false));
        assertThat(service().export(5).documents()).isEmpty();
    }
    @Test void quotaFailurePreservesPriorOutput() throws Exception {
        Files.writeString(temp.resolve("drive.json"),"previous-synthetic-output");
        when(gateway.list(5)).thenThrow(new GmailFailure(403,"rate","RATE_LIMIT"));
        assertThatThrownBy(()->service().export(5)).isInstanceOf(GmailFailure.class);
        assertThat(Files.readString(temp.resolve("drive.json"))).isEqualTo("previous-synthetic-output");
    }
    @Test void unknownProviderModeFailsBeforeFileListing() {
        when(gateway.dataMode()).thenReturn(null);
        assertThatThrownBy(()->service().export(5)).isInstanceOf(GmailFailure.class).hasMessage("Google provider mode is invalid");
        verify(gateway,never()).list(anyInt());
    }
    @Test void sandboxBoundaryFailureAbortsRatherThanExportingFailedDocument()throws Exception {
        Files.writeString(temp.resolve("drive.json"),"previous-synthetic-output");
        var file=DriveFixtures.file("file",DriveExtractor.DOCS,"M1_REJECTED_CANARY");
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("synthetic-account",List.of(file),false));
        when(gateway.content(file)).thenThrow(new GmailFailure(403,"Sandbox access denied",com.e9rental.config.GoogleSandboxPolicy.DENIAL));
        assertThatThrownBy(()->service().export(5)).hasMessage("Sandbox access denied");
        assertThat(Files.readString(temp.resolve("drive.json"))).isEqualTo("previous-synthetic-output");
    }
}
