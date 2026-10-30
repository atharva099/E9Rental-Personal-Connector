package com.e9rental.controller;
import com.e9rental.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/whatsapp/simulated")
public class WhatsAppSimulatedController {
    private final WhatsAppIngestionService ingestion;private final WhatsAppExportService export;private final ObjectMapper parser;private final WhatsAppMetadataService metadata;
    public WhatsAppSimulatedController(WhatsAppIngestionService ingestion,WhatsAppExportService export,ObjectMapper json) {
        this(ingestion,export,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public WhatsAppSimulatedController(WhatsAppIngestionService ingestion,WhatsAppExportService export,ObjectMapper json,WhatsAppMetadataService metadata){
        this.ingestion=ingestion;this.export=export;this.metadata=metadata;parser=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(8192).build());
    }
    @PostMapping(value="/events",consumes="application/json")
    public ResponseEntity<?> event(HttpServletRequest request) {
        JsonNode event;
        try {
            byte[] body=request.getInputStream().readNBytes(16385);
            if(body.length>16384)return ResponseEntity.status(413).body(Map.of("status","INVALID","data_mode","SIMULATED","reason","PAYLOAD_SIZE_LIMIT"));
            event=parser.readTree(body);
        }catch(Exception e){return ResponseEntity.badRequest().body(Map.of("status","INVALID","data_mode","SIMULATED","reason","INVALID_JSON"));}
        var result=ingestion.ingest(event);
        int status=switch(result.status()){case "ACCEPTED"->202;case "DUPLICATE"->200;case "REJECTED"->403;case "CONFLICT"->409;default->400;};
        return ResponseEntity.status(status).body(result);
    }
    @PostMapping(value="/groups",consumes="application/json")
    public ResponseEntity<?> metadata(HttpServletRequest request){
        JsonNode event;
        try{byte[] body=request.getInputStream().readNBytes(16385);if(body.length>16384)return ResponseEntity.status(413).body(Map.of("error","Metadata payload too large"));event=parser.readTree(body);}
        catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Invalid metadata payload"));}
        if(metadata==null)throw new com.e9rental.connector.GmailFailure(503,"Metadata unavailable");var result=metadata.ingest(event);
        int status=switch(result.status()){case "ACCEPTED"->202;case "DUPLICATE"->200;case "REJECTED"->403;case "CONFLICT"->409;default->400;};return ResponseEntity.status(status).body(result);
    }
    @PostMapping("/export")
    public Map<String,Object> export() {
        var result=export.export();
        return Map.of("status","EXPORTED","data_mode","SIMULATED","live_integration",false,"message_count",result.messageCount(),"group_count",result.groups().size());
    }
}
