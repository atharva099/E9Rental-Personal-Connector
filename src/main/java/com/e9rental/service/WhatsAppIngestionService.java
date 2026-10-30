package com.e9rental.service;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.WhatsAppMessageStore;
import com.e9rental.model.WhatsAppMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class WhatsAppIngestionService {
    public record Result(String status,String dataMode,String reason){}
    private static final Set<String> FIELDS=Set.of("data_mode","group_id","sender_id","message_id","timestamp","content");
    private final WhatsAppPolicy policy;private final WhatsAppMessageStore store;private final Clock clock;
    public WhatsAppIngestionService(WhatsAppPolicy policy,WhatsAppMessageStore store,Clock clock){this.policy=policy;this.store=store;this.clock=clock;}
    public Result ingest(JsonNode event) {
        policy.requireSimulation();
        if(event==null || !event.isObject())return result("INVALID","INVALID_ENVELOPE");
        // Security boundary: inspect group identity only until the allowlist passes.
        JsonNode groupNode=event.path("group_id");
        if(!groupNode.isTextual() || !WhatsAppPolicy.syntheticId(groupNode.textValue()))return result("INVALID","INVALID_GROUP_ID");
        String group=groupNode.textValue();
        if(!policy.selected(group))return result("REJECTED","GROUP_NOT_SELECTED");
        var names=event.fieldNames();while(names.hasNext())if(!FIELDS.contains(names.next()))return result("INVALID","UNSUPPORTED_FIELD");
        for(String name:FIELDS)if(!event.path(name).isTextual())return result("INVALID","MISSING_OR_INVALID_FIELD");
        if(!"SIMULATED".equals(event.path("data_mode").textValue()))return result("INVALID","SIMULATED_MARKER_REQUIRED");
        String sender=event.path("sender_id").textValue(),id=event.path("message_id").textValue(),content=event.path("content").textValue();
        if(!WhatsAppPolicy.syntheticId(sender) || !WhatsAppPolicy.syntheticId(id))return result("INVALID","INVALID_MESSAGE_IDENTITY");
        if(content.isBlank() || content.length()>4096 || !StandardCharsets.UTF_8.newEncoder().canEncode(content) || content.codePoints().anyMatch(c->(c<32 && c!='\n' && c!='\t') || c==127))
            return result("INVALID","INVALID_CONTENT");
        Instant at;
        try {at=Instant.parse(event.path("timestamp").textValue());}
        catch(DateTimeException e){return result("INVALID","INVALID_TIMESTAMP");}
        if(at.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || at.isAfter(clock.instant().plusSeconds(300)))return result("INVALID","INVALID_TIMESTAMP");
        String identity=policy.tenant()+"\u0000"+policy.account()+"\u0000"+group+"\u0000"+id;
        String document=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String fingerprint=GmailAnalyzer.hash(sender+"\u0000"+at+"\u0000"+content);
        var saved=store.save(new WhatsAppMessage(document,policy.tenant(),policy.account(),group,sender,id,at,content,clock.instant(),fingerprint));
        return result(saved.name(),saved==WhatsAppMessageStore.SaveResult.CONFLICT ? "DUPLICATE_ID_DIFFERENT_CONTENT" : "SELECTED_GROUP");
    }
    private Result result(String status,String reason){return new Result(status,"SIMULATED",reason);}
}
