package com.e9rental.connector;
import com.e9rental.service.*;
import com.e9rental.config.GoogleSandboxPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class DriveGateway {
    private static final String BASE="https://www.googleapis.com/drive/v3";
    private static final String FIELDS="id,name,mimeType,createdTime,modifiedTime,version,parents,trashed,driveId,shortcutDetails,webViewLink,size,capabilities(canDownload)";
    private final GoogleTransport transport;
    private final DriveOAuthService oauth;
    private final DriveExtractor extractor;
    private final GoogleSandboxPolicy sandbox;
    public DriveGateway(GoogleTransport transport,DriveOAuthService oauth,DriveExtractor extractor,GoogleSandboxPolicy sandbox) {
        this.transport=transport;this.oauth=oauth;this.extractor=extractor;this.sandbox=sandbox;
    }
    public String dataMode() { return transport.dataMode(); }
    public void requireEnabled() { sandbox.requireDrive(); }
    public record ListedFiles(String accountId,List<JsonNode> files,boolean incompleteSearch) {}
    public ListedFiles list(int maxFiles) {
        if (maxFiles<1 || maxFiles>25) throw new IllegalArgumentException("File limit must be 1..25");
        sandbox.requireDrive();
        String token=oauth.accessToken();
        JsonNode about=get(BASE+"/about?fields=user(permissionId)",token);
        String account=about.path("user").path("permissionId").asText("");
        if (account.isBlank()) throw new GmailFailure(502,"Drive account identity unavailable");
        sandbox.checkDriveAccount(account);
        checkRoot(token);
        String formats=java.util.stream.Stream.of(DriveExtractor.DOCS,DriveExtractor.SHEETS,DriveExtractor.DOCX,DriveExtractor.XLSX,
            "application/pdf","application/msword","application/vnd.ms-excel").map(m->"mimeType = '"+m+"'").collect(java.util.stream.Collectors.joining(" or "));
        String query="\'"+sandbox.root()+"\' in parents and trashed = false and ("+formats+")";
        List<JsonNode> files=new ArrayList<>();Set<String> seenIds=new HashSet<>(), seenPages=new HashSet<>();String page=null;
        boolean incomplete=false;
        for(int count=0;count<20;count++) {
            String url=BASE+"/files?q="+enc(query)+"&spaces=drive&corpora=user&pageSize="+Math.min(100,maxFiles)+
                "&orderBy=modifiedTime%20desc&fields="+enc("nextPageToken,incompleteSearch,files("+FIELDS+")")+
                (page==null ? "" : "&pageToken="+enc(page));
            JsonNode result=get(url,token);
            incomplete|=result.path("incompleteSearch").asBoolean(false);
            for(JsonNode file:result.path("files")) {
                String id=file.path("id").asText("");
                if (id.isBlank()) throw new GmailFailure(502,"Drive file identity missing");
                sandbox.checkFile(file);
                if (!extractor.supported(file.path("mimeType").asText())) GoogleSandboxPolicy.deny();
                if (seenIds.add(id)) files.add(file);
                if (files.size()>=maxFiles) return new ListedFiles(account,List.copyOf(files),incomplete);
            }
            page=result.path("nextPageToken").asText(null);
            if (page==null || page.isBlank()) return new ListedFiles(account,List.copyOf(files),incomplete);
            if (!seenPages.add(page)) throw new GmailFailure(502,"Repeated Drive page token");
        }
        throw new GmailFailure(503,"Drive verification page limit reached");
    }
    public byte[] content(JsonNode file) {
        sandbox.requireDrive();
        // Caller-supplied parents are never authority. Re-read identity/root and real metadata.
        String id=file==null ? "" : file.path("id").asText();
        if(!GoogleSandboxPolicy.providerId(id))GoogleSandboxPolicy.deny();
        String token=oauth.accessToken();
        var about=get(BASE+"/about?fields=user(permissionId)",token);
        sandbox.checkDriveAccount(about.path("user").path("permissionId").asText());
        checkRoot(token);
        JsonNode current=metadata(id,token);sandbox.checkFile(current);
        if(!id.equals(current.path("id").asText()) || !extractor.supported(current.path("mimeType").asText()) ||
            !current.path("mimeType").equals(file.path("mimeType")) ||
            !current.path("version").equals(file.path("version")) || !current.path("capabilities").path("canDownload").asBoolean(false))GoogleSandboxPolicy.deny();
        String export=extractor.exportMime(current.path("mimeType").asText());
        String url=BASE+"/files/"+enc(id)+(export==null ? "?alt=media" : "/export?mimeType="+enc(export));
        byte[] bytes=transport.download(url,token,20*1024*1024);
        // Catch observable moves/changes during download before any bytes reach extraction/storage.
        JsonNode after=metadata(id,token);sandbox.checkFile(after);
        if(!id.equals(after.path("id").asText()) || !current.path("version").equals(after.path("version")) ||
            !current.path("mimeType").equals(after.path("mimeType")))GoogleSandboxPolicy.deny();
        return bytes;
    }
    private JsonNode metadata(String id,String token){return get(BASE+"/files/"+enc(id)+"?fields="+enc(FIELDS),token);}
    private void checkRoot(String token){
        sandbox.checkRoot(get(BASE+"/files/"+enc(sandbox.root())+"?fields="+enc("id,mimeType,parents,trashed,driveId"),token));
    }
    private JsonNode get(String url,String token) {
        try {
            JsonNode result=transport.get(url,token);
            if (result==null) throw new GmailFailure(502,"Invalid Drive response");
            return result;
        } catch (GmailFailure e) { throw new GmailFailure(e.status(),"Drive metadata read failed",e.reason()); }
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
}
