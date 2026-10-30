package com.e9rental.service;
import com.e9rental.connector.GmailFailure;
import com.e9rental.model.*;
import com.e9rental.model.GmailOutput.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
@Service
public class GmailAnalyzer {
    public GmailOutput analyze(List<JsonNode> threads, String mailbox, String tenant, String account,
            LocalDate referenceDate, Instant now, String dataMode) {
        List<MessageResult> results=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for (JsonNode thread:threads) {
            if (thread==null || !thread.path("messages").isArray()) throw new GmailFailure(502,"Incomplete Gmail thread response");
            for (JsonNode message:thread.path("messages")) {
                String id=message.path("id").asText("");
                if (id.isBlank() || message.path("threadId").asText("").isBlank()) throw new GmailFailure(502,"Gmail message identity missing");
                if (!seen.add(id)) continue;
                JsonNode payload=message.path("payload");
                String title=header(payload,"Subject"), text=body(payload);
                List<String> labels=labels(message);
                boolean inbound=inbound(message,mailbox);
                Instant occurred=timestamp(message);
                boolean unanswered=inbound && occurred!=null &&
                    !occurred.isAfter(referenceDate.atStartOfDay(ZoneOffset.UTC).toInstant().minus(Duration.ofDays(5))) &&
                    !repliedAfter(message,thread.path("messages"),referenceDate.atStartOfDay(ZoneOffset.UTC).toInstant());
                var category=classify(title,text,header(payload,"List-Unsubscribe"),labels);
                String hash=hash(text);
                String version=hash(message.toString());
                String documentId=UUID.nameUUIDFromBytes((tenant+"\u0000"+account+"\u0000GMAIL\u0000"+id).getBytes(StandardCharsets.UTF_8)).toString();
                Map<String,Object> metadata=new LinkedHashMap<>();
                metadata.put("schema_version","1"); metadata.put("subject",title);
                metadata.put("from",header(payload,"From")); metadata.put("to",header(payload,"To"));
                metadata.put("labels",labels); metadata.put("extraction_status",text.isBlank() ? "EMPTY_OR_UNSUPPORTED" : "EXTRACTED");
                SourceRecord source=new SourceRecord(documentId,tenant,account,"GMAIL",id,message.path("threadId").asText(),
                    title,text,"text/plain",hash,version,occurred,null,occurred,now,now,null,metadata);
                List<Attachment> attachments=new ArrayList<>(); collectAttachments(payload,attachments);
                Citation citation=new Citation("S"+(results.size()+1),documentId,version,"GMAIL",title,"gmail:message:"+id,
                    "https://mail.google.com/mail/u/0/#all/"+id,now);
                results.add(new MessageResult(source,category.name,category.reasons,inbound,unanswered,List.copyOf(attachments),citation));
            }
        }
        return new GmailOutput("1.0",dataMode,now,referenceDate,OAuthService.SCOPE,tenant,account,threads.size(),results.size(),List.copyOf(results),"FULL",null);
    }
    public Category classify(String subject,String body,String unsubscribe,List<String> labels) {
        String value=(subject+" "+body).toLowerCase(Locale.ROOT);
        if (!unsubscribe.isBlank() || labels.contains("CATEGORY_PROMOTIONS") ||
                Pattern.compile("\\b(newsletter|unsubscribe|limited time|discount|promotion)\\b").matcher(value).find())
            return new Category("marketing",List.of("Promotional label, unsubscribe header or marketing keyword"));
        if (Pattern.compile("\\b(quotation|quote|invoice|purchase order|pricing|sales|rfq|proposal|order confirmation)\\b").matcher(value).find())
            return new Category("sales",List.of("Sales or transaction keyword in subject/body"));
        return new Category("other",List.of("No configured sales or marketing signal"));
    }
    public record Category(String name,List<String> reasons) {}
    private boolean inbound(JsonNode message,String mailbox) {
        var labels=labels(message);
        if (labels.contains("SENT") || labels.contains("DRAFT") || labels.contains("SPAM") || labels.contains("TRASH")) return false;
        String from=header(message.path("payload"),"From").toLowerCase(Locale.ROOT);
        String own=mailbox.toLowerCase(Locale.ROOT);
        return !from.equals(own) && !from.contains("<"+own+">");
    }
    private boolean repliedAfter(JsonNode original,JsonNode messages,Instant reference) {
        Instant at=timestamp(original);
        for (JsonNode candidate: messages) {
            Instant later=timestamp(candidate);
            if (labels(candidate).contains("SENT") && later!=null && at!=null && later.isAfter(at) && !later.isAfter(reference)) return true;
        }
        return false;
    }
    private static Instant timestamp(JsonNode m) {
        if (!m.hasNonNull("internalDate")) return null;
        try { return Instant.ofEpochMilli(Long.parseLong(m.path("internalDate").asText())); }
        catch (NumberFormatException e) { throw new GmailFailure(502,"Invalid Gmail timestamp"); }
    }
    private static List<String> labels(JsonNode m) {
        List<String> values=new ArrayList<>(); m.path("labelIds").forEach(n -> values.add(n.asText())); return values;
    }
    private static String header(JsonNode part,String name) {
        for (JsonNode h:part.path("headers")) if (name.equalsIgnoreCase(h.path("name").asText())) return h.path("value").asText("");
        return "";
    }
    private static String body(JsonNode part) {
        if (!part.path("filename").asText("").isBlank()) return "";
        String mime=part.path("mimeType").asText("");
        String data=part.path("body").path("data").asText("");
        if ((mime.equals("text/plain") || mime.equals("text/html")) && !data.isBlank()) {
            try {
                Charset charset=StandardCharsets.UTF_8;
                var match=Pattern.compile("charset=[\"']?([A-Za-z0-9_-]+)",Pattern.CASE_INSENSITIVE).matcher(header(part,"Content-Type"));
                if (match.find()) charset=Charset.forName(match.group(1));
                String text=new String(Base64.getUrlDecoder().decode(data),charset).replace("\r\n","\n");
                if (mime.equals("text/html")) text=text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("(?s)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&");
                return text.strip();
            } catch (IllegalArgumentException e) { throw new GmailFailure(502,"Unsupported Gmail text encoding"); }
        }
        List<String> plain=new ArrayList<>(), other=new ArrayList<>();
        for (JsonNode child:part.path("parts")) {
            String value=body(child);
            if (!value.isBlank()) {
                if (child.path("mimeType").asText().equals("text/plain")) plain.add(value); else other.add(value);
            }
        }
        if (mime.equals("multipart/alternative") && !plain.isEmpty()) return String.join("\n",plain);
        plain.addAll(other); return String.join("\n",plain);
    }
    private static void collectAttachments(JsonNode part,List<Attachment> result) {
        String filename=part.path("filename").asText("");
        if (!filename.isBlank()) {
            List<String> reasons=new ArrayList<>();
            Map<String,String> rules=new LinkedHashMap<>();
            rules.put("invoice","Invoice filename"); rules.put("quotation|quote","Quotation filename");
            rules.put("purchase[ _-]*order|\\bpo[ _.-]?\\d*\\b","Purchase order filename");
            rules.put("contract|agreement","Contract/agreement filename");
            rules.put("\\bkyc\\b|passport|aadhaar|aadhar|pan[ _-]*card|identity[ _-]*proof","KYC/identity document filename");
            String normalized=filename.toLowerCase(Locale.ROOT).replace('_',' ');
            rules.forEach((pattern,reason) -> { if (Pattern.compile(pattern).matcher(normalized).find()) reasons.add(reason); });
            result.add(new Attachment(filename,part.path("mimeType").asText("application/octet-stream"),
                part.path("body").path("size").asLong(0),part.path("body").path("attachmentId").asText(null),
                !reasons.isEmpty(),List.copyOf(reasons)));
        }
        part.path("parts").forEach(child -> collectAttachments(child,result));
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
