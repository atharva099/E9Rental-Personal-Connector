package com.e9rental;

import com.e9rental.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Small deterministic Google-shaped HTTP fixture, not official challenge data/schema or live Google. */
final class SyntheticGoogleProvider implements AutoCloseable {
    final HttpServer server;final ExecutorService workers;
    volatile int revision=0;
    volatile boolean wrongGmail=false,outsideDrive=false,empty=false,error=false;
    final AtomicInteger tokenRequests=new AtomicInteger(),gmailContentReads=new AtomicInteger(),driveDownloads=new AtomicInteger();
    final Map<String,ObjectNode> files=new LinkedHashMap<>();final Map<String,byte[]> content=new HashMap<>();
    SyntheticGoogleProvider()throws Exception {
        add("m2-docs",DriveExtractor.DOCS,"Synthetic rental invoice","Rental invoice amount is USD 1200.".getBytes(StandardCharsets.UTF_8));
        add("m2-sheets",DriveExtractor.SHEETS,"Accounts sheet",book(false,"Invoice accounting receipt"));
        add("m2-pdf","application/pdf","Synthetic invoice PDF",DriveFixtures.pdf());
        add("m2-word",DriveExtractor.DOCX,"Sales quotation",DriveFixtures.word());
        add("m2-xlsx",DriveExtractor.XLSX,"Verification workbook",book(false,"KYC verification identity"));
        add("m2-xls","application/vnd.ms-excel","Marketing metrics",book(true,"Marketing campaign brochure"));
        add("m2-office",DriveExtractor.DOCS,"Office meeting minutes","Office meeting minutes and room schedule.".getBytes(StandardCharsets.UTF_8));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool(task->{Thread t=new Thread(task,"synthetic-google-http");t.setDaemon(true);return t;});
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();
    }
    int port(){return server.getAddress().getPort();}
    void add(String id,String mime,String title,byte[] bytes){files.put(id,DriveFixtures.file(id,mime,title));content.put(id,bytes);}
    static byte[] book(boolean legacy,String text)throws Exception {
        try(Workbook book=legacy?new HSSFWorkbook():new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            book.createSheet("Synthetic").createRow(0).createCell(0).setCellValue(text);book.write(bytes);return bytes.toByteArray();
        }
    }
    static Map<String,String> query(String raw){
        Map<String,String> result=new HashMap<>();if(raw!=null)for(String pair:raw.split("&")){
            String[] p=pair.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");
        }return result;
    }
    static ObjectNode email(String id,String thread,String date,String subject,String body,String...labels){
        var m=Fixtures.message(id,date,labels);m.put("threadId",thread);var p=(ObjectNode)m.path("payload");
        ((ObjectNode)p.withArray("headers").get(0)).put("value",subject);
        if(Arrays.asList(labels).contains("SENT"))((ObjectNode)p.withArray("headers").get(1)).put("value","owner@example.test");
        p.withObject("/body").put("data",Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)));return m;
    }
    ObjectNode thread(String id){
        var t=Fixtures.JSON.createObjectNode();t.put("id",id);var a=t.putArray("messages");
        switch(id){
            case "m2-thread-invoice" -> {
                var m=email("m2-invoice",id,"2026-10-10T00:00:00Z","Rental invoice","", "INBOX");var p=(ObjectNode)m.path("payload");
                p.withObject("/body").remove("data");p.withObject("/body").put("attachmentId","m2-text");
                var parts=p.putArray("parts");int i=0;
                for(String name:List.of("invoice_m2.pdf","purchase_order_01.pdf","quotation_01.docx","contract_signed.docx","kyc_identity.pdf")){
                    var attachment=parts.addObject();attachment.put("filename",name);attachment.put("mimeType","application/octet-stream");attachment.putObject("body").put("size",25).put("attachmentId","synthetic-binary-"+(++i));
                }a.add(m);
            }
            case "m2-thread-marketing" -> a.add(email("m2-newsletter",id,"2026-10-15T00:00:00Z","Weekly newsletter","Newsletter discount information.","INBOX","CATEGORY_PROMOTIONS"));
            case "m2-thread-other" -> a.add(email("m2-note",id,"2026-10-15T00:00:00Z","Office update","Office opens on Monday.","INBOX"));
            case "m2-thread-replied" -> {
                a.add(email("m2-rfq",id,"2026-10-08T00:00:00Z","Quotation request","Quotation request for crane rental.","INBOX"));
                a.add(email("m2-reply",id,"2026-10-09T00:00:00Z","Quotation response","Quotation response sent.","SENT"));
            }
            default -> throw new IllegalArgumentException();
        }return t;
    }
    void handle(HttpExchange exchange)throws IOException {
        try{
            String path=exchange.getRequestURI().getPath();var q=query(exchange.getRequestURI().getRawQuery());
            if(path.equals("/token")){
                if(!exchange.getRequestMethod().equals("POST")){json(exchange,405,"{}");return;}
                var form=query(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String refresh=form.get("refresh_token");boolean drive="synthetic-drive-refresh".equals(refresh);
                if(!"refresh_token".equals(form.get("grant_type")) || (!drive && !"synthetic-gmail-refresh".equals(refresh))){json(exchange,401,"{}");return;}
                tokenRequests.incrementAndGet();var n=Fixtures.JSON.createObjectNode();n.put("access_token",drive?"synthetic-drive-read-token":"synthetic-gmail-read-token");n.put("expires_in",3600);
                n.put("scope",drive?DriveOAuthService.DRIVE_SCOPE:OAuthService.SCOPE);json(exchange,200,n);return;
            }
            String expected=path.startsWith("/drive/")?"Bearer synthetic-drive-read-token":"Bearer synthetic-gmail-read-token";
            if(!exchange.getRequestMethod().equals("GET") || !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))){json(exchange,401,"{}");return;}
            if(path.endsWith("/profile")){json(exchange,200,"{\"emailAddress\":\""+(wrongGmail?"not-approved@example.test":"owner@example.test")+"\"}");return;}
            if(path.equals("/drive/v3/about")){json(exchange,200,"{\"user\":{\"permissionId\":\"synthetic-permission\"}}");return;}
            if(path.equals("/drive/v3/files/synthetic-folder")){json(exchange,200,SandboxBoundaryTest.root());return;}
            if(error){json(exchange,403,"{\"error\":{\"message\":\"M2_REJECTED_CANARY\"}}");return;}
            if(path.equals("/gmail/v1/users/me/threads")){
                var n=Fixtures.JSON.createObjectNode();var list=n.putArray("threads");
                if(!empty){
                    boolean page2="synthetic page+2".equals(q.get("pageToken"));
                    for(String id:page2?List.of("m2-thread-other","m2-thread-replied"):List.of("m2-thread-invoice","m2-thread-marketing"))list.addObject().put("id",id);
                    if(!page2)n.put("nextPageToken","synthetic page+2");
                }json(exchange,200,n);return;
            }
            if(path.startsWith("/gmail/v1/users/me/threads/")){gmailContentReads.incrementAndGet();json(exchange,200,thread(path.substring(path.lastIndexOf('/')+1)));return;}
            if(path.equals("/gmail/v1/users/me/messages/m2-invoice/attachments/m2-text")){
                var n=Fixtures.JSON.createObjectNode();n.put("data",Base64.getUrlEncoder().withoutPadding().encodeToString(("Rental invoice amount is USD "+(revision==0?"1200":"1350")+".").getBytes(StandardCharsets.UTF_8)));json(exchange,200,n);return;
            }
            if(path.equals("/drive/v3/files")){
                if(!q.getOrDefault("q","").contains("'synthetic-folder' in parents")){json(exchange,403,"{}");return;}
                var n=Fixtures.JSON.createObjectNode();n.put("incompleteSearch",false);var list=n.putArray("files");
                if(!empty){
                    boolean page2="drive page+2".equals(q.get("pageToken"));int index=0;
                    for(var entry:files.entrySet()){
                        if((index++>=3)!=page2)continue;
                        var f=entry.getValue().deepCopy();if(entry.getKey().equals("m2-docs"))f.put("version",revision==0?"7":"8");
                        if(outsideDrive){f.putArray("parents").add("outside-folder");f.put("name","M2_REJECTED_CANARY");}list.add(f);
                    }if(!page2)n.put("nextPageToken","drive page+2");
                }json(exchange,200,n);return;
            }
            if(path.startsWith("/drive/v3/files/")){
                String id=path.substring("/drive/v3/files/".length()).replace("/export","");
                var f=files.get(id);if(f==null){json(exchange,404,"{}");return;}
                if(path.endsWith("/export") || "media".equals(q.get("alt"))){
                    driveDownloads.incrementAndGet();byte[] bytes=id.equals("m2-docs")?("Rental invoice amount is USD "+(revision==0?"1200":"1350")+".").getBytes(StandardCharsets.UTF_8):content.get(id);
                    exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;
                }
                var current=f.deepCopy();if(id.equals("m2-docs"))current.put("version",revision==0?"7":"8");json(exchange,200,current);return;
            }
            json(exchange,404,"{}");
        }catch(Exception e){json(exchange,500,"{\"error\":\"Synthetic provider failure\"}");}
        finally{exchange.close();}
    }
    static void json(HttpExchange x,int status,Object value)throws IOException {
        byte[] bytes=(value instanceof String s?s:Fixtures.JSON.writeValueAsString(value)).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
    }
    @Override public void close(){server.stop(0);workers.shutdownNow();}
}
