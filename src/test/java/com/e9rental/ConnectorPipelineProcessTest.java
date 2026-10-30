package com.e9rental;

import com.e9rental.model.*;
import com.e9rental.service.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Separate backend JVM, real transport/OAuth refresh and local provider TCP; exclusively synthetic data. */
class ConnectorPipelineProcessTest {
    @TempDir Path runtime;
    SyntheticGoogleProvider provider;
    Process backend;int backendPort,launch=0;
    final List<ProcessOutputGuard> processOutputs=new ArrayList<>();
    final String apiToken="synthetic-m2-local-api-token-only";
    String key;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void setup()throws Exception {
        provider=new SyntheticGoogleProvider();byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);key=Base64.getEncoder().encodeToString(bytes);
        Files.createDirectories(runtime.resolve("outputs"));Files.createDirectories(runtime.resolve("unified"));Files.createDirectories(runtime.resolve("whatsapp"));
        new EncryptedTokenStore(key,runtime.resolve("gmail.enc").toString()).save(Fixtures.JSON.writeValueAsBytes(Map.of("scope",OAuthService.SCOPE,"refresh_token","synthetic-gmail-refresh")));
        new EncryptedTokenStore(key,runtime.resolve("drive.enc").toString()).save(Fixtures.JSON.writeValueAsBytes(Map.of("scope",DriveOAuthService.DRIVE_SCOPE,"refresh_token","synthetic-drive-refresh")));
        startBackend();
    }
    void startBackend()throws Exception {
        int current=++launch;Path ready=runtime.resolve("ready-"+current),argsFile=runtime.resolve("java-"+current+".args");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        List<String> args=new ArrayList<>(List.of("-Dsynthetic.provider.port="+provider.port(),"-Dsynthetic.ready.file="+ready,"-cp",cp,SyntheticPipelineApplication.class.getName(),
            "--server.port=0","--server.address=127.0.0.1","--spring.main.banner-mode=off",
            "--SANDBOX_GOOGLE_APPROVED=true","--SANDBOX_GMAIL_ACCOUNT=owner@example.test","--SANDBOX_DRIVE_ACCOUNT_ID=synthetic-permission","--SANDBOX_DRIVE_ROOT_FOLDER_ID=synthetic-folder",
            "--GOOGLE_CLIENT_ID=synthetic-desktop-client","--GOOGLE_CLIENT_SECRET=synthetic-client-secret-not-real",
            "--GOOGLE_REDIRECT_URI=http://127.0.0.1:8080/","--DRIVE_REDIRECT_URI=http://127.0.0.1:8080/api/drive/oauth/callback",
            "--TOKEN_ENCRYPTION_KEY="+key,"--GMAIL_TOKEN_FILE="+runtime.resolve("gmail.enc"),"--DRIVE_TOKEN_FILE="+runtime.resolve("drive.enc"),
            "--GOOGLE_CONNECTOR_DATA_MODE=SYNTHETIC",
            "--LOCAL_API_TOKEN="+apiToken,"--PRACTICE_TENANT_ID=test-tenant","--GMAIL_REFERENCE_DATE=2026-10-16","--GMAIL_REQUEST_DELAY_MS=0",
            "--GMAIL_OUTPUT_FILE="+runtime.resolve("outputs/gmail.json"),"--DRIVE_OUTPUT_FILE="+runtime.resolve("outputs/drive.json"),
            "--UNIFIED_STORE_URL=jdbc:h2:file:"+runtime.resolve("unified/index").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE",
            "--WHATSAPP_STORE_URL=jdbc:h2:file:"+runtime.resolve("whatsapp/messages").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE"));
        // Java argfile avoids Windows command-line length limits; every value is this test's synthetic configuration.
        Files.writeString(argsFile,String.join("\n",args.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList()));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        backend=new ProcessBuilder(java.toString(),"@"+argsFile).redirectErrorStream(true).start();
        processOutputs.add(new ProcessOutputGuard(backend.getInputStream()));
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        Integer publishedPort=null;
        while(System.nanoTime()<deadline && backend.isAlive() && publishedPort==null){
            if(Files.exists(ready)){
                try{
                    int candidate=Integer.parseInt(Files.readString(ready));
                    if(candidate>0 && candidate<=65535)publishedPort=candidate;
                }catch(NumberFormatException|IOException ignored){
                    // The child publishes this marker after startup; file creation can precede its complete contents.
                }
            }
            if(publishedPort==null)Thread.sleep(100);
        }
        assertThat(backend.isAlive() && publishedPort!=null).as("Synthetic child backend startup; log withheld on failure").isTrue();
        backendPort=publishedPort;assertThat(call("GET","/health",null).statusCode()).isEqualTo(200);
    }
    void stopBackend()throws Exception {
        if(backend!=null){
            Process child=backend;
            try {
                child.destroy();
                if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){
                    child.destroyForcibly();
                    assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).as("Synthetic child backend termination").isTrue();
                }
                processOutputs.get(processOutputs.size()-1).finish();
            } finally {
                if(child.isAlive()){
                    child.destroyForcibly();
                    child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                }
                closeQuietly(child.getOutputStream());
                closeQuietly(child.getInputStream());
                closeQuietly(child.getErrorStream());
                backend=null;
            }
        }
    }
    private static void closeQuietly(Closeable stream){try{stream.close();}catch(IOException ignored){}}
    @AfterEach void stop()throws Exception {try{stopBackend();}finally{if(provider!=null)provider.close();}}
    HttpResponse<String> call(String method,String path,String body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+backendPort+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+apiToken);
        if(body!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,String body)throws Exception {
        var result=call(method,path,body);assertThat(result.statusCode()).as("Synthetic pipeline endpoint status").isEqualTo(200);return Fixtures.JSON.readTree(result.body());
    }
    JsonNode exportAndIngest(String source)throws Exception {
        String path=source.equals("GMAIL")?"/api/gmail/export?referenceDate=2026-10-16":"/api/drive/export?maxFiles=25";
        ok("POST",path,"");return ok("POST","/api/unified/ingest?source="+source,"");
    }
    QuestionAnswer ask(String question)throws Exception {
        return Fixtures.JSON.treeToValue(ok("POST","/api/qa/ask",Fixtures.JSON.writeValueAsString(Map.of("question",question))),QuestionAnswer.class);
    }
    List<SearchRecord> records()throws Exception {
        return Fixtures.JSON.convertValue(ok("GET","/api/unified/search?limit=100",null),new com.fasterxml.jackson.core.type.TypeReference<List<SearchRecord>>(){});
    }
    void verifyCitations(QuestionAnswer answer,String amount)throws Exception {
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.mode()).isEqualTo("DETERMINISTIC_EXTRACTIVE");
        assertThat(answer.llmGenerated()).isFalse();assertThat(answer.externalProcessing()).isFalse();var rows=records();
        assertThat(answer.evidence()).isNotEmpty();
        for(var e:answer.evidence()){
            var r=rows.stream().filter(x->x.source().documentId().equals(e.documentId())).findFirst().orElseThrow();var s=r.source();
            assertThat(e.sourceType()).isEqualTo(s.sourceType());assertThat(e.sourceId()).isEqualTo(s.externalId());assertThat(e.connectorAccountId()).isEqualTo(s.connectorAccountId());
            assertThat(s.tenantId()).isEqualTo("test-tenant");assertThat(e.sourceVersion()).isEqualTo(s.sourceVersion());assertThat(e.locator()).isEqualTo(r.citation().locator());
            assertThat(e.sourceUrl()).isEqualTo(r.citation().sourceUrl());assertThat(GmailAnalyzer.hash(s.contentText())).isEqualTo(s.contentHash());
            assertThat(e.startOffset()).isGreaterThanOrEqualTo(0);assertThat(e.endOffset()).isLessThanOrEqualTo(s.contentText().length());
            assertThat(e.excerpt()).isEqualTo(s.contentText().substring(e.startOffset(),e.endOffset()));assertThat(e.excerpt()).contains(amount);
            assertThat(answer.answer()).contains("["+e.reference()+"] "+e.excerpt());
        }
    }
    void assertNoCanary()throws Exception {
        assertThat(ok("GET","/api/unified/search?keyword=M2_REJECTED_CANARY",null).isEmpty()).isTrue();
        var absent=ask("Show M2 rejected canary records");assertThat(absent.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(absent.evidence()).isEmpty();
        for(ProcessOutputGuard output:processOutputs)output.assertNoCanaryAfterQuietPeriod();
    }
    /** Drains child output continuously, retaining only a small overlap window and canary match flags. */
    static final class ProcessOutputGuard {
        private static final List<byte[]> CANARIES=List.of("M2_REJECTED_CANARY","Rental invoice amount is USD","synthetic-gmail-refresh","synthetic-drive-refresh")
            .stream().map(s->s.getBytes(StandardCharsets.UTF_8)).toList();
        private static final int MAX_CARRY=CANARIES.stream().mapToInt(a->a.length).max().orElse(1)-1;
        private final InputStream input;
        private final Object signal=new Object();
        private final Set<String> detected=java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile IOException readFailure;
        private volatile boolean eof;
        private volatile long lastReadNanos=System.nanoTime();
        private final Thread reader;

        ProcessOutputGuard(InputStream input){
            this.input=input;
            reader=new Thread(this::consume,"synthetic-backend-output");
            reader.setDaemon(true);
            reader.start();
        }
        private void consume(){
            byte[] buffer=new byte[8192],carry=new byte[MAX_CARRY];int carryLength=0;
            try {
                int count;
                while((count=input.read(buffer))!=-1){
                    byte[] window=new byte[carryLength+count];
                    System.arraycopy(carry,0,window,0,carryLength);System.arraycopy(buffer,0,window,carryLength,count);
                    for(byte[] canary:CANARIES)if(contains(window,canary))detected.add(new String(canary,StandardCharsets.UTF_8));
                    carryLength=Math.min(MAX_CARRY,window.length);
                    if(carryLength>0)System.arraycopy(window,window.length-carryLength,carry,0,carryLength);
                    lastReadNanos=System.nanoTime();
                    synchronized(signal){signal.notifyAll();}
                }
            }catch(IOException e){readFailure=e;}
            finally{eof=true;synchronized(signal){signal.notifyAll();}}
        }
        private static boolean contains(byte[] data,byte[] pattern){
            outer:for(int i=0;i<=data.length-pattern.length;i++){
                for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j])continue outer;
                return true;
            }
            return false;
        }
        void assertNoCanaryAfterQuietPeriod()throws Exception{
            long quietPeriod=Duration.ofMillis(150).toNanos(),quietStart=System.nanoTime(),deadline=quietStart+Duration.ofSeconds(2).toNanos();
            synchronized(signal){
                while(!eof){
                    long now=System.nanoTime(),quietSince=Math.max(quietStart,lastReadNanos);
                    if(now-quietSince>=quietPeriod)break;
                    long remaining=Math.min(deadline-now,quietPeriod-(now-quietSince));if(remaining<=0)break;
                    signal.wait(Math.max(1,Math.min(50,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
                }
            }
            if(readFailure!=null)throw new IOException("Could not inspect synthetic child output",readFailure);
            assertThat(detected).as("Sensitive synthetic values must not appear in child logs").isEmpty();
        }
        void finish()throws Exception{
            reader.join(5000);
            if(reader.isAlive()){
                input.close();reader.join(1000);
            }
            assertThat(reader.isAlive()).as("Synthetic child output reader termination").isFalse();
            if(readFailure!=null)throw new IOException("Could not drain synthetic child output",readFailure);
            assertThat(detected).as("Sensitive synthetic values must not appear in child logs").isEmpty();
        }
    }
    void report(String name,Map<String,Object> data)throws Exception {
        var report=new TreeMap<String,Object>(data);report.put("provider_mode","LOCAL_SYNTHETIC_HTTP");report.put("live_google_verified",false);report.put("backend_mode","SEPARATE_JVM");
        Path folder=Path.of("target/m2-pipeline");Files.createDirectories(folder);Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(name+".json").toFile(),report);
    }
    @Test void bothConnectorsTraverseTransportClassifiersStoreSearchAndCitations()throws Exception {
        var g=exportAndIngest("GMAIL");var d=exportAndIngest("DRIVE");assertThat(g.path("inserted").asInt()).isEqualTo(5);assertThat(d.path("inserted").asInt()).isEqualTo(7);
        GmailOutput gmail=Fixtures.JSON.readValue(runtime.resolve("outputs/gmail.json").toFile(),GmailOutput.class);
        assertThat(gmail.dataMode()).isEqualTo("SYNTHETIC");
        assertThat(gmail.scope()).isEqualTo(OAuthService.SCOPE);assertThat(gmail.threadCount()).isEqualTo(4);assertThat(gmail.messageCount()).isEqualTo(5);
        Map<String,GmailOutput.MessageResult> byId=new HashMap<>();gmail.messages().forEach(m->byId.put(m.source().externalId(),m));
        assertThat(byId.get("m2-invoice").classification()).isEqualTo("sales");assertThat(byId.get("m2-invoice").unansweredFiveDays()).isTrue();
        assertThat(byId.get("m2-newsletter").classification()).isEqualTo("marketing");assertThat(byId.get("m2-note").classification()).isEqualTo("other");
        assertThat(byId.get("m2-rfq").unansweredFiveDays()).isFalse();assertThat(byId.get("m2-reply").inbound()).isFalse();
        assertThat(byId.get("m2-invoice").attachments()).hasSize(5).allSatisfy(a->{assertThat(a.businessCritical()).isTrue();assertThat(a.reasons()).isNotEmpty();});
        DriveOutput drive=Fixtures.JSON.readValue(runtime.resolve("outputs/drive.json").toFile(),DriveOutput.class);
        assertThat(drive.dataMode()).isEqualTo("SYNTHETIC");
        assertThat(drive.scope()).isEqualTo(DriveOAuthService.DRIVE_SCOPE);assertThat(drive.fileCount()).isEqualTo(7);assertThat(drive.extractedCount()).isEqualTo(7);
        assertThat(drive.documents()).extracting(DriveOutput.Document::category).containsExactlyInAnyOrder("accounts","accounts","accounts","sales","verification","marketing","office");
        assertThat(drive.documents()).allSatisfy(x->{assertThat(x.extractionStatus()).isEqualTo("EXTRACTED");assertThat(x.classificationReason()).isNotBlank();assertThat(x.source().parentExternalId()).isEqualTo("synthetic-folder");});
        Map<String,DriveOutput.Document> docs=new HashMap<>();drive.documents().forEach(x->docs.put(x.source().externalId(),x));
        Map<String,String> expectedText=Map.of("m2-docs","Rental invoice amount is USD 1200.","m2-sheets","Invoice accounting receipt",
            "m2-pdf","Invoice for synthetic rental","m2-word","Office table text","m2-xlsx","KYC verification identity",
            "m2-xls","Marketing campaign brochure","m2-office","Office meeting minutes and room schedule.");
        expectedText.forEach((id,text)->assertThat(docs.get(id).source().contentText()).contains(text));
        assertThat(records()).hasSize(12);
        assertThat(ok("GET","/api/unified/search?source=GMAIL&classification=marketing",null)).hasSize(1);
        assertThat(ok("GET","/api/unified/search?source=DRIVE&classification=sales",null)).hasSize(1);
        for(String source:List.of("Gmail","Drive")){var answer=ask("What is the rental invoice amount in "+source+"?");verifyCitations(answer,"USD 1200");assertThat(answer.evidence()).hasSize(1);}
        var multi=ask("What is rental invoice amount across Gmail and Drive?");verifyCitations(multi,"USD 1200");assertThat(multi.evidence()).hasSize(2);
        Map<String,String> documentQuestions=Map.of("m2-sheets","Show accounting receipt records from Drive",
            "m2-pdf","Show invoice PDF records from Drive","m2-word","Show quotation records from Drive",
            "m2-xlsx","Show KYC records from Drive","m2-xls","Show campaign records from Drive","m2-office","Show room schedule records from Drive");
        for(var item:documentQuestions.entrySet()){
            var a=ask(item.getValue());verifyCitations(a,"");assertThat(a.evidence()).singleElement().satisfies(e->assertThat(e.sourceId()).isEqualTo(item.getKey()));
        }
        for(var item:Map.of("m2-note","Show office records from Gmail","m2-newsletter","Show newsletter records from Gmail").entrySet()){
            var a=ask(item.getValue());verifyCitations(a,"");assertThat(a.evidence()).singleElement().satisfies(e->assertThat(e.sourceId()).isEqualTo(item.getKey()));
        }
        var missing=ask("Who is the CEO of Neptune?");assertThat(missing.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(missing.evidence()).isEmpty();
        assertThat(provider.tokenRequests.get()).isEqualTo(2);assertThat(provider.gmailContentReads.get()).isEqualTo(4);assertThat(provider.driveDownloads.get()).isEqualTo(7);
        assertNoCanary();report("full-flow",Map.of("gmail_messages",5,"gmail_threads",4,"critical_attachments",5,"drive_documents",7,"drive_extracted",7,"indexed",12,"positive_questions",11,"citations_verified",12,"empty_evidence_abstained",true));
    }
    @Test void duplicateIngestionUpdatesAndIndependentJvmRestartPreserveEvidence()throws Exception {
        exportAndIngest("GMAIL");exportAndIngest("DRIVE");
        assertThat(exportAndIngest("GMAIL").path("unchanged").asInt()).isEqualTo(5);assertThat(exportAndIngest("DRIVE").path("unchanged").asInt()).isEqualTo(7);
        var original=records().stream().filter(r->r.source().externalId().equals("m2-invoice")).findFirst().orElseThrow();
        provider.revision=1;
        assertThat(exportAndIngest("GMAIL").path("updated").asInt()).isEqualTo(1);assertThat(exportAndIngest("DRIVE").path("updated").asInt()).isEqualTo(1);
        var updated=records().stream().filter(r->r.source().externalId().equals("m2-invoice")).findFirst().orElseThrow();assertThat(updated.source().documentId()).isEqualTo(original.source().documentId());assertThat(updated.source().sourceVersion()).isNotEqualTo(original.source().sourceVersion());
        var answer=ask("What is rental invoice amount across Gmail and Drive?");verifyCitations(answer,"USD 1350");assertThat(answer.answer()).doesNotContain("USD 1200");
        Map<String,String> before=new TreeMap<>();for(var row:records())before.put(row.source().documentId(),row.source().sourceVersion()+":"+row.source().contentHash());
        long oldPid=backend.pid();stopBackend();startBackend();assertThat(backend.pid()).isNotEqualTo(oldPid);
        Map<String,String> after=new TreeMap<>();for(var row:records())after.put(row.source().documentId(),row.source().sourceVersion()+":"+row.source().contentHash());assertThat(after).isEqualTo(before);assertThat(after).hasSize(12);
        verifyCitations(ask("What is rental invoice amount across Gmail and Drive?"),"USD 1350");
        // Actual encrypted synthetic grants survive restart and refresh via real transport, not a mocked token service.
        assertThat(exportAndIngest("GMAIL").path("unchanged").asInt()).isEqualTo(5);assertThat(exportAndIngest("DRIVE").path("unchanged").asInt()).isEqualTo(7);assertThat(provider.tokenRequests.get()).isEqualTo(4);
        assertNoCanary();report("restart-update",Map.of("indexed",12,"unchanged_gmail",5,"unchanged_drive",7,"updated_gmail",1,"updated_drive",1,"independent_jvm_restart",true,"no_reingestion_needed",true,"synthetic_token_refreshes",4));
    }
    @Test void emptyProviderAndOutOfScopeOrFailedReadsNeverInventRecords()throws Exception {
        provider.empty=true;assertThat(exportAndIngest("GMAIL").path("inserted").asInt()).isZero();assertThat(exportAndIngest("DRIVE").path("inserted").asInt()).isZero();assertThat(records()).isEmpty();
        assertThat(ask("What is rental invoice amount across Gmail and Drive?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        byte[] g=Files.readAllBytes(runtime.resolve("outputs/gmail.json")),d=Files.readAllBytes(runtime.resolve("outputs/drive.json"));
        provider.empty=false;provider.wrongGmail=true;int reads=provider.gmailContentReads.get();
        assertThat(call("POST","/api/gmail/export","").statusCode()).isEqualTo(403);assertThat(provider.gmailContentReads.get()).isEqualTo(reads);
        provider.outsideDrive=true;assertThat(call("POST","/api/drive/export?maxFiles=25","").statusCode()).isEqualTo(403);assertThat(provider.driveDownloads.get()).isZero();
        provider.wrongGmail=false;provider.outsideDrive=false;provider.error=true;
        for(String source:List.of("gmail","drive")){var result=call("POST","/api/"+source+"/export","");assertThat(result.statusCode()).isEqualTo(502);assertThat(result.body()).doesNotContain("M2_REJECTED_CANARY");}
        assertThat(Files.readAllBytes(runtime.resolve("outputs/gmail.json"))).isEqualTo(g);assertThat(Files.readAllBytes(runtime.resolve("outputs/drive.json"))).isEqualTo(d);assertThat(records()).isEmpty();assertNoCanary();
        report("denial-empty",Map.of("empty_export_ingest",true,"wrong_account_before_content",true,"out_of_scope_before_download",true,"provider_errors_sanitized",true,"prior_outputs_preserved",true,"indexed",0));
    }
    @Test void testRouterCannotFallBackToExternalNetwork()throws Exception {
        var factory=SyntheticPipelineApplication.loopbackFactory(provider.port());
        assertThatThrownBy(()->factory.createRequest(URI.create("https://example.com/"),org.springframework.http.HttpMethod.GET)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->factory.createRequest(URI.create("http://gmail.googleapis.com/"),org.springframework.http.HttpMethod.GET)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->factory.createRequest(new URI("https","synthetic-userinfo","www.googleapis.com",-1,"/",null,null),org.springframework.http.HttpMethod.GET)).isInstanceOf(IOException.class);
    }
}
