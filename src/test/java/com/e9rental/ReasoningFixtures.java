package com.e9rental;
import com.e9rental.model.*;
import com.e9rental.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
/** Deliberately fabricated acceptance dataset, separate from the original USD 1200 demo. */
public final class ReasoningFixtures {
    public static final Instant REFERENCE=Instant.parse("2026-10-17T12:00:00Z");
    public static final String GROUP="sim-group-rentals";
    public static ObjectNode message(String id,String thread,String at,String subject,String body,String... labels){
        var m=Fixtures.message(id,at,labels);m.put("threadId",thread);var p=(ObjectNode)m.path("payload");
        p.withArray("headers").removeAll();p.withArray("headers").addObject().put("name","Subject").put("value",subject);p.withArray("headers").addObject().put("name","From").put("value","sender@example.test");
        p.withObject("body").put("data",Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)));return m;
    }
    public static List<JsonNode> gmailThreads(){
        List<JsonNode> threads=new ArrayList<>();
        threads.add(Fixtures.thread(message("sale-a","sale-thread","2026-09-10T00:00:00Z","Confirmed invoice","Confirmed sale invoice INV-A dated 2026-09-10 total USD 1000.","SENT")));
        threads.add(Fixtures.thread(message("pending-old","p1","2026-10-10T08:00:00Z","Quotation request","Please provide a quotation for rental equipment.","INBOX")));
        threads.add(Fixtures.thread(message("pending-boundary","p2","2026-10-12T00:00:00Z","RFQ request","Please send pricing for the crane.","INBOX")));
        threads.add(Fixtures.thread(message("replied-sale","replied","2026-10-09T00:00:00Z","Quotation request","Please provide pricing.","INBOX"),message("sent-reply","replied","2026-10-11T00:00:00Z","Quotation response","Here is the quotation.","SENT")));
        threads.add(Fixtures.thread(message("young-sale","young","2026-10-12T00:00:00.001Z","Quotation request","Please send a quotation.","INBOX")));
        threads.add(Fixtures.thread(message("marketing","promo","2026-10-01T00:00:00Z","Newsletter","Newsletter promotion.","INBOX")));
        threads.add(Fixtures.thread(message("outgoing","out","2026-10-01T00:00:00Z","Quotation response","Quotation attached.","SENT")));
        return threads;
    }
    public static GmailOutput gmail(){return new GmailAnalyzer().analyze(gmailThreads(),"owner@example.test","test-tenant","gmail-reasoning",LocalDate.of(2026,10,17),REFERENCE,"SYNTHETIC");
    }
    public static SearchRecord record(String id,String type,String body,Instant at){
        String account=type.equals("WHATSAPP")?"sim-account-test":type.toLowerCase(Locale.ROOT)+"-reasoning";
        var s=new SourceRecord(id,"test-tenant",account,type,id,type.equals("WHATSAPP")?GROUP:"synthetic-folder","Synthetic acceptance evidence",body,"text/plain",GmailAnalyzer.hash(body),"v1",at,at,at,REFERENCE,REFERENCE,null,Map.of("synthetic",true));
        return new SearchRecord(s,type.equals("WHATSAPP")?"SIMULATED":"SYNTHETIC",type.equals("WHATSAPP")?"unclassified":"accounts",null,Map.of(),new SearchRecord.Citation(id,"v1",type,"synthetic:"+id,null));
    }
    public static DriveOutput drive(){
        List<DriveOutput.Document> docs=new ArrayList<>();
        var contents=List.of("Confirmed sale invoice INV-B dated 2026-09-20 total USD 500.","Confirmed sale invoice INV-A dated 2026-09-10 total USD 1000.","Quotation Q-1 dated 2026-09-15 total USD 9999.","Confirmed sale invoice INV-OCT dated 2026-10-01 total USD 7000.");
        for(int i=0;i<contents.size();i++){var r=record("sale-doc-"+i,"DRIVE",contents.get(i),REFERENCE);var s=r.source();docs.add(new DriveOutput.Document(s,"accounts","Synthetic accounting statement","EXTRACTED",null,new DriveOutput.Citation("D"+i,s.documentId(),s.sourceVersion(),"DRIVE",s.title(),r.citation().locator(),null,REFERENCE)));}
        return new DriveOutput("1.0","SYNTHETIC","SYNTHETIC_FIXTURE",REFERENCE,DriveOAuthService.DRIVE_SCOPE,4,4,4,false,List.copyOf(docs));
    }
    public static List<JsonNode> events(){
        List<JsonNode> result=new ArrayList<>();
        result.add(event("sim-agree-1",GROUP,"2026-10-12T00:00:00Z","Decision SITE-A: We agreed to deliver the crane on Monday."));
        result.add(event("sim-agree-2",GROUP,"2026-10-16T08:00:00Z","Decision SITE-B: The team agreed to inspect the safety equipment."));
        result.add(event("sim-old",GROUP,"2026-10-11T23:59:59Z","Decision OLD: We agreed to use the previous schedule."));
        result.add(event("sim-proposal",GROUP,"2026-10-16T09:00:00Z","Could we deliver on Tuesday? This is a proposal."));
        result.add(event("sim-other","sim-group-operations","2026-10-16T10:00:00Z","Decision OTHER: We agreed to use another site."));
        result.add(event("sim-excluded","sim-group-excluded","2026-10-16T11:00:00Z","Decision EXCLUDED: We agreed to disclose M3_EXCLUDED_CANARY."));return result;
    }
    public static ObjectNode event(String id,String group,String at,String body){var e=WhatsAppFixtures.event(id,group,body);e.put("timestamp",at);return e;}
}
