package com.e9rental.service;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class DriveExportService {
    private final DriveGateway gateway;private final DriveExtractor extractor;private final DriveClassifier classifier;
    private final ObjectMapper json;private final Clock clock;private final String tenant,outputFile;
    public DriveExportService(DriveGateway gateway,DriveExtractor extractor,DriveClassifier classifier,ObjectMapper json,Clock clock,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant,
            @Value("${DRIVE_OUTPUT_FILE:${PERSONAL_RUNTIME_DIR:D:/E9Rental-Secrets/gmail-runtime}/outputs/drive_output.json}") String outputFile) {
        this.gateway=gateway;this.extractor=extractor;this.classifier=classifier;this.json=json;this.clock=clock;this.tenant=tenant;this.outputFile=outputFile;
    }
    public synchronized DriveOutput export(int limit) {
        gateway.requireEnabled();
        if(limit<1 || limit>25) throw new IllegalArgumentException("File limit must be 1..25");
        String dataMode=gateway.dataMode();
        if (!"LIVE".equals(dataMode) && !"SYNTHETIC".equals(dataMode)) throw new GmailFailure(500,"Google provider mode is invalid");
        var listed=gateway.list(limit);Instant now=clock.instant();
        String account="drive-"+GmailAnalyzer.hash(listed.accountId()).substring(0,24);
        List<DriveOutput.Document> results=new ArrayList<>();int extracted=0;
        for(JsonNode file:listed.files()) {
            String id=file.path("id").asText(),mime=file.path("mimeType").asText(),title=file.path("name").asText("");
            DriveExtractor.Extraction content;
            if (!file.path("capabilities").path("canDownload").asBoolean(false)) content=new DriveExtractor.Extraction("","SKIPPED","DOWNLOAD_NOT_PERMITTED");
            else if (file.path("size").asLong(0)>20L*1024*1024) content=new DriveExtractor.Extraction("","SKIPPED","FILE_SIZE_LIMIT");
            else if (!extractor.supported(mime)) content=new DriveExtractor.Extraction("","SKIPPED","UNSUPPORTED_FORMAT");
            else {
                try { content=extractor.extract(mime,gateway.content(file)); }
                catch(GmailFailure e) {
                    if(com.e9rental.config.GoogleSandboxPolicy.DENIAL.equals(e.reason())) throw e;
                    if(e.status()==401 || e.status()==429 || e.status()>=500 || e.reason().equals("RATE_LIMIT")) throw e;
                    content=new DriveExtractor.Extraction("","FAILED","CONTENT_UNAVAILABLE_OR_LIMITED");
                }
            }
            if(content.status().equals("EXTRACTED")) extracted++;
            var category=classifier.classify(title,content.text());
            String documentId=UUID.nameUUIDFromBytes((tenant+"\u0000"+account+"\u0000DRIVE\u0000"+id).getBytes(StandardCharsets.UTF_8)).toString();
            String version=file.path("version").asText(GmailAnalyzer.hash(content.text()));
            Map<String,Object> metadata=new LinkedHashMap<>();
            metadata.put("schema_version","1");metadata.put("extraction_status",content.status());metadata.put("original_mime_type",mime);
            Instant created=instant(file,"createdTime"), modified=instant(file,"modifiedTime");
            var source=new SourceRecord(documentId,tenant,account,"DRIVE",id,file.path("parents").path(0).asText(null),
                title,content.text(),mime,GmailAnalyzer.hash(content.text()),version,created,modified,modified==null ? created : modified,now,now,null,metadata);
            String url="https://drive.google.com/file/d/"+java.net.URLEncoder.encode(id,StandardCharsets.UTF_8)+"/view";
            var citation=new DriveOutput.Citation("D"+(results.size()+1),documentId,version,"DRIVE",title,"drive:file:"+id,url,now);
            results.add(new DriveOutput.Document(source,category.category(),category.reason(),content.status(),content.reason(),citation));
        }
        var output=new DriveOutput("1.0",dataMode,"LIMITED_VERIFICATION",now,DriveOAuthService.DRIVE_SCOPE,limit,results.size(),extracted,listed.incompleteSearch(),List.copyOf(results));
        try {
            Path target=Path.of(outputFile).toAbsolutePath();Files.createDirectories(target.getParent());
            Path temp=Files.createTempFile(target.getParent(),"drive-", ".json");
            try { json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),output);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch(java.io.IOException e) { throw new GmailFailure(503,"Could not write private Drive export"); }
        return output;
    }
    private static Instant instant(JsonNode file,String key) {
        String value=file.path(key).asText(null);return value==null ? null : Instant.parse(value);
    }
}
