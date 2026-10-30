package com.e9rental;
import com.e9rental.service.DriveExtractor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DriveExtractorTest {
    DriveExtractor extractor=new DriveExtractor();
    @Test void extractsGoogleDocsUtf8() {
        var result=extractor.extract(DriveExtractor.DOCS,"Office minutes\r\nNext step".getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("EXTRACTED");assertThat(result.text()).isEqualTo("Office minutes\nNext step");
    }
    @Test void extractsWordParagraphsAndTables() throws Exception {
        var result=extractor.extract(DriveExtractor.DOCX,DriveFixtures.word());
        assertThat(result.text()).contains("Sales quotation","Office table text");assertThat(result.status()).isEqualTo("EXTRACTED");
    }
    @Test void extractsSyntheticLegacyWordDocument() throws Exception {
        byte[] bytes;
        try(var input=DriveExtractorTest.class.getResourceAsStream("/fixtures/synthetic-legacy-word.doc")) {
            assertThat(input).isNotNull();
            bytes=input.readAllBytes();
        }
        var result=extractor.extract("application/msword",bytes);
        assertThat(result.status()).isEqualTo("EXTRACTED");
        assertThat(result.text()).contains("Synthetic legacy Word rental quotation","USD 875");
    }
    @Test void extractsPdfText() throws Exception {
        var result=extractor.extract("application/pdf",DriveFixtures.pdf());
        assertThat(result.text()).contains("Invoice for synthetic rental");assertThat(result.status()).isEqualTo("EXTRACTED");
    }
    @Test void extractsAllGoogleSheetsWorksheetsThroughXlsx() throws Exception {
        var result=extractor.extract(DriveExtractor.SHEETS,DriveFixtures.excel(false));
        assertThat(result.text()).contains("Sheet: Accounts","Invoice 123","Sheet: Verification","KYC synthetic");
    }
    @Test void extractsExcelXlsxAndLegacyXls() throws Exception {
        assertThat(extractor.extract(DriveExtractor.XLSX,DriveFixtures.excel(false)).status()).isEqualTo("EXTRACTED");
        assertThat(extractor.extract("application/vnd.ms-excel",DriveFixtures.excel(true)).text()).contains("Invoice 123");
    }
    @Test void usesNativeExportFormatsAndSkipsUnsupportedTypes() {
        assertThat(extractor.exportMime(DriveExtractor.DOCS)).isEqualTo("text/plain");
        assertThat(extractor.exportMime(DriveExtractor.SHEETS)).isEqualTo(DriveExtractor.XLSX);
        assertThat(extractor.supported("image/png")).isFalse();
        assertThat(extractor.extract("image/png",new byte[0]).status()).isEqualTo("SKIPPED");
    }
    @Test void corruptOrEncryptedOfficeAndPdfFilesFailSafely() {
        for(String mime:java.util.List.of(DriveExtractor.DOCX,DriveExtractor.XLSX,"application/pdf","application/msword")) {
            var result=extractor.extract(mime,"invalid synthetic bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(result.status()).isEqualTo("FAILED");assertThat(result.text()).isEmpty();
            assertThat(result.reason()).isEqualTo("UNREADABLE_ENCRYPTED_OR_INVALID_DOCUMENT");
        }
    }
    @Test void emptyAndExcessiveTextAreExplicitRatherThanFabricated() {
        assertThat(extractor.extract(DriveExtractor.DOCS,new byte[0]).status()).isEqualTo("EMPTY");
        assertThat(extractor.extract(DriveExtractor.DOCS,new byte[1_000_001]).reason()).isEqualTo("TEXT_SIZE_LIMIT");
    }
}
