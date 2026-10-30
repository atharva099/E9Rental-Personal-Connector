package com.e9rental.service;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class WhatsAppExportService {
    private final WhatsAppPolicy policy;private final WhatsAppMessageStore store;private final WhatsAppSummaryService summaries;
    private final ObjectMapper json;private final Clock clock;private final String outputFile;
    public WhatsAppExportService(WhatsAppPolicy policy,WhatsAppMessageStore store,WhatsAppSummaryService summaries,ObjectMapper json,Clock clock,
            @Value("${WHATSAPP_OUTPUT_FILE:D:/E9Rental-Secrets/gmail-runtime/outputs/whatsapp_simulated_output.json}") String outputFile) {
        this.policy=policy;this.store=store;this.summaries=summaries;this.json=json;this.clock=clock;this.outputFile=outputFile;
    }
    public synchronized WhatsAppOutput snapshot() {
        policy.requireSimulation();Instant now=clock.instant();
        var accepted=store.selected(policy.tenant(),policy.account(),policy.selectedGroups());
        var metadataByGroup=store.selectedMetadata(policy.tenant(),policy.account(),policy.selectedGroups());
        List<WhatsAppOutput.Message> records=new ArrayList<>();
        for(var message:accepted) {
            Map<String,Object> metadata=Map.of("schema_version","1","data_mode","SIMULATED","group_id",message.groupId(),"sender_id",message.senderId());
            var supplied=metadataByGroup.get(message.groupId());
            var current=new LinkedHashMap<String,Object>(metadata);current.put("group_name",supplied==null?null:supplied.groupName());current.put("metadata_version",supplied==null?null:supplied.version());
            var person=supplied==null?null:supplied.participants().stream().filter(p->p.senderId().equals(message.senderId())).findFirst().orElse(null);
            current.put("current_sender_display_name",person==null?null:person.displayName());current.put("current_sender_number",person==null?null:person.number());
            var source=new SourceRecord(message.documentId(),message.tenantId(),message.connectorAccountId(),"WHATSAPP",message.messageId(),message.groupId(),
                "Simulated group "+message.groupId(),message.content(),"text/plain",GmailAnalyzer.hash(message.content()),message.fingerprint(),
                message.timestamp(),null,message.timestamp(),message.receivedAt(),message.receivedAt(),null,current);
            var citation=new WhatsAppOutput.Citation("W"+(records.size()+1),message.documentId(),message.fingerprint(),"WHATSAPP",
                "simulated-whatsapp:group:"+message.groupId()+":message:"+message.messageId(),null,now);
            records.add(new WhatsAppOutput.Message(source,citation));
        }
        var output=new WhatsAppOutput("1.0","SIMULATED",false,"CURRENT_SELECTED_GROUPS",now,records.size(),
            List.copyOf(policy.selectedGroups()),List.copyOf(records),summaries.summarize(accepted,metadataByGroup,policy.selectedGroups()));
        return output;
    }
    public synchronized WhatsAppOutput export() {
        var output=snapshot();
        try {
            Path target=Path.of(outputFile).toAbsolutePath();Files.createDirectories(target.getParent());
            Path temp=Files.createTempFile(target.getParent(),"whatsapp-simulated-", ".json");
            try{json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),output);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temp);}
        }catch(java.io.IOException e){throw new GmailFailure(503,"Simulation output could not be written");}
        return output;
    }
}
