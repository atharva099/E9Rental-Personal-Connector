package com.e9rental;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
public final class Fixtures {
    public static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static final Instant NOW=Instant.parse("2026-10-16T00:00:00Z");
    public static List<JsonNode> threads() throws IOException {
        try (var in=Fixtures.class.getResourceAsStream("/fixtures/gmail-mailbox.json")) {
            List<JsonNode> result=new ArrayList<>(); JSON.readTree(in).path("threads").forEach(result::add); return result;
        }
    }
    public static ObjectNode message(String id,String at,String... labels) {
        var m=JSON.createObjectNode(); m.put("id",id); m.put("threadId","thread");
        m.put("internalDate",String.valueOf(Instant.parse(at).toEpochMilli()));
        var l=m.putArray("labelIds"); for (String label: labels) l.add(label);
        var p=m.putObject("payload"); p.put("mimeType","text/plain");
        p.putArray("headers").addObject().put("name","Subject").put("value","Invoice inquiry");
        p.withArray("headers").addObject().put("name","From").put("value","sender@example.test");
        p.putObject("body").put("data",Base64.getUrlEncoder().withoutPadding().encodeToString("Please respond".getBytes(StandardCharsets.UTF_8)));
        return m;
    }
    /** Packaged demo data is explicit; never inject an answer or modify a live connector snapshot. */
    public static com.e9rental.model.GmailOutput demoGmail() throws IOException {
        var demo=new ArrayList<>(threads());
        try(var in=Fixtures.class.getResourceAsStream("/fixtures/gmail-demo-invoice.json")) {
            demo.add(JSON.readTree(in));
        }
        return new com.e9rental.service.GmailAnalyzer().analyze(demo,"owner@example.test","test-tenant","gmail-test",
            LocalDate.of(2026,10,16),NOW,"SYNTHETIC");
    }
    public static JsonNode thread(JsonNode... messages) {
        var t=JSON.createObjectNode(); var a=t.putArray("messages"); for (var m:messages) a.add(m); return t;
    }
}
