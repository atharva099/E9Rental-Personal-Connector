package com.e9rental.service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Service;
@Service
public class DriveExtractor {
    public static final String DOCS="application/vnd.google-apps.document", SHEETS="application/vnd.google-apps.spreadsheet";
    public static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_CHARS=1_000_000;
    public record Extraction(String text,String status,String reason) {}
    public String exportMime(String mime) {
        return mime.equals(DOCS) ? "text/plain" : mime.equals(SHEETS) ? XLSX : null;
    }
    public boolean supported(String mime) {
        return java.util.Set.of(DOCS,SHEETS,DOCX,XLSX,"application/pdf","application/msword","application/vnd.ms-excel").contains(mime);
    }
    public Extraction extract(String mime,byte[] bytes) {
        try {
            ZipSecureFile.setMaxEntrySize(20L*1024*1024);
            String text;
            if (mime.equals(DOCS) || mime.equals("text/plain")) text=new String(bytes,StandardCharsets.UTF_8);
            else if (mime.equals("application/pdf")) {
                try (var pdf=Loader.loadPDF(bytes)) {
                    if (pdf.isEncrypted() && !pdf.getCurrentAccessPermission().canExtractContent()) return new Extraction("","SKIPPED","PDF_EXTRACTION_NOT_PERMITTED");
                    if (pdf.getNumberOfPages()>200) return new Extraction("","SKIPPED","PDF_PAGE_LIMIT");
                    text=new PDFTextStripper().getText(pdf);
                }
            } else if (mime.equals(DOCX)) {
                try (var doc=new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor=new XWPFWordExtractor(doc)) { text=extractor.getText(); }
            } else if (mime.equals("application/msword")) {
                try (var doc=new HWPFDocument(new ByteArrayInputStream(bytes)); var extractor=new WordExtractor(doc)) { text=extractor.getText(); }
            } else if (mime.equals(SHEETS) || mime.equals(XLSX) || mime.equals("application/vnd.ms-excel")) {
                StringBuilder builder=new StringBuilder(); int cells=0; DataFormatter formatter=new DataFormatter(java.util.Locale.ROOT);
                try (Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                    for (Sheet sheet:book) {
                        builder.append("Sheet: ").append(sheet.getSheetName()).append('\n');
                        for (Row row:sheet) {
                            for (Cell cell:row) {
                                if (++cells>100000 || builder.length()>MAX_CHARS) return new Extraction("","SKIPPED","SPREADSHEET_EXTRACTION_LIMIT");
                                // Use cached formula results; never evaluate formulas or external workbook links.
                                formatter.setUseCachedValuesForFormulaCells(true);
                                builder.append(formatter.formatCellValue(cell)).append('\t');
                            }
                            builder.append('\n');
                        }
                    }
                }
                text=builder.toString();
            } else return new Extraction("","SKIPPED","UNSUPPORTED_FORMAT");
            text=text.replace("\r\n","\n").replace('\r','\n').strip();
            if (text.length()>MAX_CHARS) return new Extraction("","SKIPPED","TEXT_SIZE_LIMIT");
            return new Extraction(text,text.isBlank() ? "EMPTY" : "EXTRACTED",text.isBlank() ? "NO_TEXT_OR_OCR_REQUIRED" : "LOCAL_TEXT_EXTRACTION");
        } catch (Exception e) { return new Extraction("","FAILED","UNREADABLE_ENCRYPTED_OR_INVALID_DOCUMENT"); }
    }
}
