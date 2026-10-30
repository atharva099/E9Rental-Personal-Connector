package com.e9rental;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.connector.WhatsAppMessageStore;
import com.e9rental.service.WhatsAppIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
public final class WhatsAppFixtures {
    public static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"),ZoneOffset.UTC);
    public static final String GROUPS="sim-group-rentals,sim-group-operations";
    public static WhatsAppPolicy policy(){return new WhatsAppPolicy("SIMULATED",GROUPS,"test-tenant","sim-account-test");}
    public static WhatsAppMessageStore store(){return new WhatsAppMessageStore("jdbc:h2:mem:wa_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1",Fixtures.JSON);}
    public static List<JsonNode> events() throws Exception {
        try(var in=WhatsAppFixtures.class.getResourceAsStream("/fixtures/whatsapp-simulated-events.json")){
            List<JsonNode> values=new ArrayList<>();Fixtures.JSON.readTree(in).path("events").forEach(values::add);return values;
        }
    }
    public static ObjectNode event(String id,String group,String content) {
        var event=Fixtures.JSON.createObjectNode();event.put("data_mode","SIMULATED");event.put("group_id",group);
        event.put("sender_id","sim-user-test");event.put("message_id",id);event.put("timestamp","2026-10-16T12:00:00Z");event.put("content",content);return event;
    }
    public static void acceptedFixture(WhatsAppMessageStore store) throws Exception {
        var service=new WhatsAppIngestionService(policy(),store,CLOCK);
        for(var event:events().subList(0,6))if(!service.ingest(event).status().equals("ACCEPTED"))throw new AssertionError("Fixture rejected");
    }
}
