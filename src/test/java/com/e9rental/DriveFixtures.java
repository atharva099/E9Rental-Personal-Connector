package com.e9rental;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
public final class DriveFixtures {
    public static ObjectNode file(String id,String mime,String name) {
        var n=Fixtures.JSON.createObjectNode();n.put("id",id);n.put("name",name);n.put("mimeType",mime);
        n.put("version","7");n.put("createdTime","2026-10-01T00:00:00Z");n.put("modifiedTime","2026-10-17T00:00:00Z");
        n.putArray("parents").add("synthetic-folder");n.put("trashed",false);n.putObject("capabilities").put("canDownload",true);return n;
    }
    public static byte[] word() throws IOException {
        try(var doc=new XWPFDocument();var bytes=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Sales quotation for synthetic equipment");
            var table=doc.createTable(1,1);table.getRow(0).getCell(0).setText("Office table text");
            doc.write(bytes);return bytes.toByteArray();
        }
    }
    public static byte[] excel(boolean legacy) throws IOException {
        try(org.apache.poi.ss.usermodel.Workbook book=legacy ? new HSSFWorkbook() : new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            book.createSheet("Accounts").createRow(0).createCell(0).setCellValue("Invoice 123");
            book.createSheet("Verification").createRow(0).createCell(0).setCellValue("KYC synthetic identity");
            book.write(bytes);return bytes.toByteArray();
        }
    }
    public static byte[] pdf() throws IOException {
        try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var content=new PDPageContentStream(pdf,page)) {
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(30,700);content.showText("Invoice for synthetic rental");content.endText();
            }
            pdf.save(bytes);return bytes.toByteArray();
        }
    }
}
