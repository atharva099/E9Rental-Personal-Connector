package com.e9rental;
import com.e9rental.model.*;
import com.e9rental.config.*;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Test-classpath generator: no credentials, file import, network or real provider dependency. */
public final class PublicOutputGenerator {
    static final Clock CLOCK=WhatsAppFixtures.CLOCK;
    public static List<JsonNode> events(){var events=new ArrayList<>(ReasoningFixtures.events());events.add(ReasoningFixtures.event("sim-request","sim-group-rentals","2026-10-16T12:00:00Z","Could you send the equipment quotation?"));events.add(ReasoningFixtures.event("sim-commit","sim-group-rentals","2026-10-16T12:01:00Z","I will inspect the equipment on 2026-10-20."));events.add(ReasoningFixtures.event("sim-action","sim-group-operations","2026-10-16T12:02:00Z","Action: please prepare the safety checklist."));return events;}
    public static List<JsonNode> metadata(){var a=WhatsAppMetadataTest.metadata("sim-group-rentals",1);a.put("group_name","Synthetic Site team");var b=WhatsAppMetadataTest.metadata("sim-group-operations",1);b.put("group_name","Synthetic Operations");((ObjectNode)b.path("participants").get(0)).putNull("number");return List.of(a,b);}
    static ObjectNode publicTree(Object value,String format){var root=(ObjectNode)Fixtures.JSON.valueToTree(value);root.put("practice_schema_version","2.0");root.put("provisional_schema",true);root.put("fixture_provenance","GENERATED_SYNTHETIC_FIXTURES");root.put("official_schema_available",false);root.put("practice_format",format);return root;}
    static JsonNode canonical(JsonNode node){
        if(node.isObject()){var sorted=Fixtures.JSON.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);for(String name:names)sorted.set(name,canonical(node.get(name)));return sorted;}
        if(node.isArray()){var array=Fixtures.JSON.createArrayNode();node.forEach(item->array.add(canonical(item)));return array;}return node;
    }
    public static void generate(Path directory)throws Exception{
        Files.createDirectories(directory);var policy=WhatsAppFixtures.policy();var store=WhatsAppFixtures.store();var intake=new WhatsAppIngestionService(policy,store,CLOCK);var meta=new WhatsAppMetadataService(policy,store,CLOCK,Fixtures.JSON);
        for(var event:metadata())if(!meta.ingest(event).status().equals("ACCEPTED"))throw new IllegalStateException("Synthetic metadata refused");
        for(var event:events()){String status=intake.ingest(event).status();if(event.path("group_id").asText().equals("sim-group-excluded")){if(!status.equals("REJECTED"))throw new IllegalStateException("Excluded synthetic event accepted");}else if(!status.equals("ACCEPTED"))throw new IllegalStateException("Synthetic event refused");}
        var whatsapp=new WhatsAppExportService(policy,store,new WhatsAppSummaryService(),Fixtures.JSON,CLOCK,"unused").snapshot();
        var threads=new ArrayList<>(ReasoningFixtures.gmailThreads());var message=ReasoningFixtures.message("public-critical","public-critical-thread","2026-10-10T00:00:00Z","Document review","Please review these documents.","INBOX");
        var parts=((ObjectNode)message.path("payload")).putArray("parts");for(String name:List.of("invoice.pdf","purchase_order.pdf","quotation.docx","contract.pdf","kyc.pdf")){var a=parts.addObject();a.put("filename",name);a.put("mimeType","application/octet-stream");a.putObject("body").put("size",12).put("attachmentId","synthetic-attachment-"+name);}
        threads.add(Fixtures.thread(message));var gmail=new GmailAnalyzer().analyze(threads,"owner@example.test","test-tenant","gmail-reasoning",LocalDate.of(2026,10,17),CLOCK.instant(),"SYNTHETIC");
        var gmailTree=publicTree(gmail,"gmail-practice-2.0");var unanswered=gmailTree.putArray("unanswered_messages");for(var m:gmail.messages())if(m.unansweredFiveDays()){var entry=unanswered.addObject();entry.put("source_id",m.source().externalId());entry.put("document_id",m.source().documentId());entry.set("citation",Fixtures.JSON.valueToTree(m.citation()));}gmailTree.put("unanswered_count",unanswered.size());
        List<DriveOutput.Document> docs=new ArrayList<>();List<String> bodies=List.of("Quotation for synthetic equipment sales.","Newsletter campaign for synthetic rentals.","KYC verification for a synthetic identity.","Office meeting notes for the synthetic team.","Confirmed sale invoice INV-B dated 2026-09-20 total USD 500.");var classifier=new DriveClassifier();
        for(int i=0;i<bodies.size();i++){String id=UUID.nameUUIDFromBytes(("public-drive-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),body=bodies.get(i),version=GmailAnalyzer.hash(body);var category=classifier.classify("Synthetic document",body);
            var source=new SourceRecord(id,policy.tenant(),"drive-public-synthetic","DRIVE","public-drive-"+i,"synthetic-folder","Synthetic document",body,"text/plain",GmailAnalyzer.hash(body),version,CLOCK.instant(),CLOCK.instant(),CLOCK.instant(),CLOCK.instant(),CLOCK.instant(),null,Map.of("schema_version","1","extraction_status","EXTRACTED","original_mime_type","text/plain","synthetic",true));
            docs.add(new DriveOutput.Document(source,category.category(),category.reason(),"EXTRACTED","SYNTHETIC_TEXT_FIXTURE",new DriveOutput.Citation("D"+(i+1),id,version,"DRIVE",source.title(),"synthetic-drive:file:public-drive-"+i,null,CLOCK.instant())));
        }
        var drive=new DriveOutput("1.0","SYNTHETIC","SYNTHETIC_FIXTURE",CLOCK.instant(),DriveOAuthService.DRIVE_SCOPE,docs.size(),docs.size(),docs.size(),false,List.copyOf(docs));
        var json=Fixtures.JSON.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true).configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        for(var entry:Map.of("whatsapp_output.json",publicTree(whatsapp,"whatsapp-practice-2.0"),"gmail_output.json",gmailTree,"drive_output.json",publicTree(drive,"drive-practice-2.0")).entrySet()){
            String canonical=json.writerWithDefaultPrettyPrinter().writeValueAsString(canonical(entry.getValue())).replace("\r\n","\n")+"\n";Files.writeString(directory.resolve(entry.getKey()),canonical,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
