package com.e9rental.service;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.WhatsAppMessageStore;
import com.e9rental.model.WhatsAppGroupMetadata;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class WhatsAppMetadataService {
    private final WhatsAppPolicy policy;private final WhatsAppMessageStore store;private final Clock clock;private final ObjectMapper json;
    public WhatsAppMetadataService(WhatsAppPolicy policy,WhatsAppMessageStore store,Clock clock,ObjectMapper json){this.policy=policy;this.store=store;this.clock=clock;this.json=json;}
    public WhatsAppIngestionService.Result ingest(JsonNode event){
        policy.requireSimulation();if(event==null || !event.isObject())return result("INVALID","INVALID_ENVELOPE");
        var group=event.path("group_id");if(!group.isTextual() || !WhatsAppPolicy.syntheticId(group.textValue()))return result("INVALID","INVALID_GROUP_ID");
        if(!policy.selected(group.textValue()))return result("REJECTED","GROUP_NOT_SELECTED");
        try {
            var fields=Set.of("data_mode","group_id","group_name","version","updated_at","participants");var names=event.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw new IllegalArgumentException();
            if(!"SIMULATED".equals(event.path("data_mode").textValue()) || !event.path("version").isIntegralNumber() || !event.path("version").canConvertToLong() || event.path("version").longValue()<1 || !event.path("updated_at").isTextual() || !event.path("participants").isArray() || event.path("participants").size()>50)throw new IllegalArgumentException();
            String name=nullable(event.get("group_name"),128);Instant updated=Instant.parse(event.path("updated_at").textValue());if(updated.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || updated.isAfter(clock.instant().plusSeconds(300)))throw new IllegalArgumentException();
            var participants=new TreeMap<String,WhatsAppGroupMetadata.Participant>();
            for(var p:event.path("participants")){
                if(!p.isObject())throw new IllegalArgumentException();var keys=p.fieldNames();while(keys.hasNext())if(!Set.of("sender_id","display_name","number").contains(keys.next()))throw new IllegalArgumentException();
                String id=p.path("sender_id").textValue();if(!WhatsAppPolicy.syntheticId(id))throw new IllegalArgumentException();String display=nullable(p.get("display_name"),128),number=nullable(p.get("number"),40);
                if(number!=null && !number.matches("sim-number-[A-Za-z0-9_-]{1,24}"))throw new IllegalArgumentException();
                if(participants.putIfAbsent(id,new WhatsAppGroupMetadata.Participant(id,display,number))!=null)throw new IllegalArgumentException();
            }
            long version=event.path("version").longValue();var roster=List.copyOf(participants.values());String fingerprint=GmailAnalyzer.hash(json.writeValueAsString(Arrays.asList(group.textValue(),name,version,updated.toString(),roster)));
            var saved=store.saveMetadata(new WhatsAppGroupMetadata(policy.tenant(),policy.account(),group.textValue(),name,version,updated,roster,fingerprint));return result(saved.name(),saved==WhatsAppMessageStore.SaveResult.CONFLICT?"STALE_OR_CONFLICTING_METADATA":"SELECTED_GROUP");
        }catch(java.io.IOException|IllegalArgumentException|DateTimeException e){return result("INVALID","INVALID_METADATA");}
    }
    private String nullable(JsonNode value,int limit){if(value==null || value.isNull())return null;if(!value.isTextual() || value.textValue().isBlank() || value.textValue().length()>limit || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue()) || value.textValue().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return value.textValue();}
    private WhatsAppIngestionService.Result result(String status,String reason){return new WhatsAppIngestionService.Result(status,"SIMULATED",reason);}
}
