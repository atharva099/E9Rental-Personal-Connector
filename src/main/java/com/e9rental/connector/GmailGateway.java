package com.e9rental.connector;
import com.e9rental.service.OAuthService;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.model.GmailRetrievalMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class GmailGateway {
    public static final int QUESTION_MAX_MESSAGES=20;
    public static final int SYNC_MAX_CHANGED_MESSAGES=5;
    private static final int QUESTION_MAX_ATTACHMENT_FETCHES=5;
    private static final int QUESTION_MAX_MIME_PARTS=500;
    private static final String BASE="https://gmail.googleapis.com/gmail/v1/users/me";
    private final GoogleTransport transport;
    private final GmailViewerTransport viewerTransport;
    private final OAuthService oauth;
    private final int maxPages;
    private final GoogleSandboxPolicy sandbox;
    @org.springframework.beans.factory.annotation.Autowired
    public GmailGateway(GoogleTransport transport,GmailViewerTransport viewerTransport,OAuthService oauth,
            @Value("${GMAIL_MAX_PAGES:100}") int maxPages,GoogleSandboxPolicy sandbox) {
        this.transport=transport;this.viewerTransport=viewerTransport;this.oauth=oauth;this.maxPages=maxPages;this.sandbox=sandbox;
        if (maxPages<1 || maxPages>1000) throw new IllegalArgumentException("Invalid page limit");
    }
    public GmailGateway(GoogleTransport transport,OAuthService oauth,int maxPages,GoogleSandboxPolicy sandbox) {
        this(transport,new GmailViewerTransport(),oauth,maxPages,sandbox);
    }
    public String dataMode() { return transport.dataMode(); }
    public Mailbox read() {
        return read(0);
    }
    public Mailbox read(int maxThreads) {
        if (maxThreads<0 || maxThreads>1000) throw new IllegalArgumentException("Invalid verification thread limit");
        sandbox.requireGmail();
        String token=oauth.accessToken();
        JsonNode profile=transport.get(BASE+"/profile",token);
        if (profile==null || profile.path("emailAddress").asText("").isBlank()) throw new GmailFailure(502,"Invalid Gmail profile response");
        sandbox.checkGmailAccount(profile.path("emailAddress").asText());
        List<JsonNode> threads=new ArrayList<>();
        Set<String> seenPages=new HashSet<>(), seenThreads=new HashSet<>();
        String page=null;
        for (int count=0; ; count++) {
            if (count>=maxPages) throw new GmailFailure(503,"Mailbox exceeds page limit; increase GMAIL_MAX_PAGES and retry; no partial export written");
            String url=BASE+"/threads?maxResults=100&includeSpamTrash=false"+(page==null ? "" : "&pageToken="+enc(page));
            JsonNode result=transport.get(url,token);
            if (result==null) throw new GmailFailure(502,"Invalid Gmail list response");
            for (JsonNode entry: result.path("threads")) {
                String id=entry.path("id").asText("");
                if (id.isBlank()) throw new GmailFailure(502,"Gmail thread identity missing");
                if (seenThreads.add(id)) {
                    JsonNode thread=transport.get(BASE+"/threads/"+enc(id)+"?format=full",token);
                    if (thread==null || !thread.path("messages").isArray()) throw new GmailFailure(502,"Incomplete Gmail thread response");
                    for (JsonNode message:thread.path("messages")) hydrateText(message.path("payload"),message.path("id").asText(),token);
                    threads.add(thread);
                    if (maxThreads>0 && threads.size()>=maxThreads) return new Mailbox(profile.path("emailAddress").asText(),List.copyOf(threads));
                }
            }
            page=result.path("nextPageToken").asText(null);
            if (page==null || page.isBlank()) break;
            if (!seenPages.add(page)) throw new GmailFailure(502,"Repeated Gmail page token; export aborted");
        }
        return new Mailbox(profile.path("emailAddress").asText(),List.copyOf(threads));
    }
    /** Bounded direct question-time search. No direct Gmail request is retried. */
    public Mailbox searchQuestion(GmailSearchPlan plan) {
        String query=buildQuery(plan);
        sandbox.requireGmail();
        GmailRequestBudget budget=GmailRequestBudget.directQuestion();
        OAuthService.GmailQuestionToken tokenResult=oauth.accessTokenForGmailQuestion();
        String token=tokenResult.accessToken();
        JsonNode profile=transport.getQuestionOnce(BASE+"/profile",token,budget,GmailRequestBudget.Operation.PROFILE);
        if(profile==null || profile.path("emailAddress").asText("").isBlank())throw new GmailFailure(502,"Invalid Gmail profile response");
        // Identity is checked before either the message list or message content is requested.
        sandbox.checkGmailAccount(profile.path("emailAddress").asText());
        String listUrl=BASE+"/messages"+(query.isBlank()?"":"?q="+enc(query))+ (query.isBlank()?"?":"&")+
            "maxResults="+plan.maxMessages()+"&includeSpamTrash=false";
        JsonNode result=transport.getQuestionOnce(listUrl,token,budget,GmailRequestBudget.Operation.LIST);
        if(result==null || (result.has("messages") && !result.path("messages").isArray()))throw new GmailFailure(502,"Invalid Gmail search response");
        boolean partialResults=!result.path("nextPageToken").asText("").isBlank();
        JsonNode hits=result.path("messages");
        if(hits.isMissingNode())hits=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if(hits.size()>plan.maxMessages() || hits.size()>QUESTION_MAX_MESSAGES)throw new GmailFailure(502,"Gmail search exceeded its result limit","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
        Map<String,com.fasterxml.jackson.databind.node.ObjectNode> byThread=new LinkedHashMap<>();
        Set<String> seenMessages=new HashSet<>();int[] attachmentFetches={0},mimePartsVisited={0};
        for(JsonNode entry:hits) {
            String id=entry.path("id").asText("");
            if(!id.matches("[A-Za-z0-9_-]{1,200}") || !seenMessages.add(id))throw new GmailFailure(502,"Invalid Gmail message identity");
            JsonNode message=transport.getQuestionOnce(BASE+"/messages/"+enc(id)+"?format=full",token,budget,GmailRequestBudget.Operation.MESSAGE);
            if(message==null || !id.equals(message.path("id").asText()) ||
                    !message.path("threadId").asText("").matches("[A-Za-z0-9_-]{1,200}") || !message.path("payload").isObject())
                throw new GmailFailure(502,"Incomplete Gmail message response");
            hydrateTextBounded(message.path("payload"),id,token,attachmentFetches,mimePartsVisited,budget,true);
            String threadId=message.path("threadId").asText();
            var thread=byThread.computeIfAbsent(threadId,key->{var node=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();node.put("id",key);node.set("messages",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());return node;});
            ((com.fasterxml.jackson.databind.node.ArrayNode)thread.path("messages")).add(message);
        }
        GmailRequestBudget.Snapshot counts=budget.snapshot();
        int messageRequests=hits.size(),attachmentRequests=attachmentFetches[0];
        GmailRetrievalMetrics metrics=new GmailRetrievalMetrics(2+messageRequests+attachmentRequests,1,1,
            messageRequests,attachmentRequests,tokenResult.refreshAttempts(),counts.responseBytes(),hits.size());
        return new Mailbox(profile.path("emailAddress").asText(),List.copyOf(byThread.values()),partialResults,metrics);
    }

    /** Deadline-aware route used only by explicitly approved Live Gmail Search requests. */
    public Mailbox searchDirect(GmailSearchPlan plan,GmailRequestBudget budget) {
        if(plan==null || budget==null)throw new IllegalArgumentException("A typed search and deadline are required");
        String query=buildQuery(plan);
        sandbox.requireGmail();budget.ensureWithinDeadline();
        OAuthService.GmailQuestionToken tokenResult=oauth.accessTokenForGmailViewer(budget);
        String token=tokenResult.accessToken();budget.ensureWithinDeadline();
        JsonNode profile=viewerTransport.get(viewerTransport.gmailUri("/profile"),token,budget,GmailRequestBudget.Operation.PROFILE);
        budget.ensureWithinDeadline();
        if(profile==null || profile.path("emailAddress").asText("").isBlank())throw new GmailFailure(502,"Invalid Gmail profile response");
        sandbox.checkGmailAccount(profile.path("emailAddress").asText());
        budget.ensureWithinDeadline();
        String listUrl="/messages"+(query.isBlank()?"":"?q="+enc(query))+(query.isBlank()?"?":"&")+
            "maxResults="+plan.maxMessages()+"&includeSpamTrash=false";
        JsonNode result=viewerTransport.get(viewerTransport.gmailUri(listUrl),token,budget,GmailRequestBudget.Operation.LIST);
        budget.ensureWithinDeadline();
        if(result==null || (result.has("messages") && !result.path("messages").isArray()))throw new GmailFailure(502,"Invalid Gmail search response");
        boolean partialResults=!result.path("nextPageToken").asText("").isBlank();
        JsonNode hits=result.path("messages");
        if(hits.isMissingNode())hits=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if(hits.size()>plan.maxMessages() || hits.size()>QUESTION_MAX_MESSAGES)throw new GmailFailure(502,"Gmail search exceeded its result limit","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
        Map<String,com.fasterxml.jackson.databind.node.ObjectNode> byThread=new LinkedHashMap<>();
        Set<String> seenMessages=new HashSet<>();int[] attachmentFetches={0},mimePartsVisited={0};
        for(JsonNode entry:hits) {
            budget.ensureWithinDeadline();
            String id=entry.path("id").asText("");
            if(!id.matches("[A-Za-z0-9_-]{1,200}") || !seenMessages.add(id))throw new GmailFailure(502,"Invalid Gmail message identity");
            JsonNode message=viewerTransport.get(viewerTransport.gmailUri("/messages/"+enc(id)+"?format=full"),token,budget,
                GmailRequestBudget.Operation.MESSAGE);
            budget.ensureWithinDeadline();
            if(message==null || !id.equals(message.path("id").asText()) ||
                    !message.path("threadId").asText("").matches("[A-Za-z0-9_-]{1,200}") || !message.path("payload").isObject())
                throw new GmailFailure(502,"Incomplete Gmail message response");
            hydrateTextBounded(message.path("payload"),id,token,attachmentFetches,mimePartsVisited,budget,HydrationTransport.DEADLINE_AWARE);
            budget.ensureWithinDeadline();
            String threadId=message.path("threadId").asText();
            var thread=byThread.computeIfAbsent(threadId,key->{var node=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();node.put("id",key);node.set("messages",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());return node;});
            ((com.fasterxml.jackson.databind.node.ArrayNode)thread.path("messages")).add(message);
        }
        budget.ensureWithinDeadline();
        GmailRequestBudget.Snapshot counts=budget.snapshot();
        int messageRequests=hits.size(),attachmentRequests=attachmentFetches[0];
        GmailRetrievalMetrics metrics=new GmailRetrievalMetrics(2+messageRequests+attachmentRequests,1,1,
            messageRequests,attachmentRequests,tokenResult.refreshAttempts(),counts.responseBytes(),hits.size());
        return new Mailbox(profile.path("emailAddress").asText(),List.copyOf(byThread.values()),partialResults,metrics);
    }

    /** Performs one explicitly approved, one-message view after the selection registry has authorized it. */
    public JsonNode readSelectedQuestionMessage(String messageId,String expectedAccount) {
        return readSelectedQuestionMessage(messageId,expectedAccount,GmailRequestBudget.singleMessageView());
    }
    public JsonNode readSelectedQuestionMessage(String messageId,String expectedAccount,GmailRequestBudget budget) {
        if(messageId==null || !messageId.matches("[A-Za-z0-9_-]{1,200}") || expectedAccount==null || expectedAccount.isBlank())
            throw new GmailFailure(404,"Gmail message selection is invalid or expired","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        sandbox.requireGmail();
        budget.ensureWithinDeadline();
        OAuthService.GmailQuestionToken tokenResult=oauth.accessTokenForGmailViewer(budget);
        String token=tokenResult.accessToken();
        budget.ensureWithinDeadline();
        JsonNode profile=viewerTransport.get(viewerTransport.gmailUri("/profile"),token,budget,GmailRequestBudget.Operation.PROFILE);
        budget.ensureWithinDeadline();
        if(profile==null || profile.path("emailAddress").asText("").isBlank())
            throw new GmailFailure(502,"Invalid Gmail profile response");
        sandbox.checkGmailAccount(profile.path("emailAddress").asText());
        if(!expectedAccount.equals(sandbox.gmailRecordAccount()))throw new GmailFailure(404,"Gmail message selection is invalid or expired","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        JsonNode message=viewerTransport.get(viewerTransport.gmailUri("/messages/"+enc(messageId)+"?format=full"),token,budget,
            GmailRequestBudget.Operation.MESSAGE);
        budget.ensureWithinDeadline();
        if(message==null || !messageId.equals(message.path("id").asText()) || !message.path("payload").isObject())
            throw new GmailFailure(502,"Selected Gmail message is unavailable","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        return message;
    }

    /** Render only validated plan components into Gmail's query language. */
    public static String buildQuery(GmailSearchPlan plan) {
        if(plan==null || plan.scope()!=GmailSearchPlan.MailScope.REGULAR_MAIL)
            throw new IllegalArgumentException("Unsupported Gmail mail scope");
        List<String> parts=new ArrayList<>();
        if(plan.senderEmail()!=null)parts.add("from:"+plan.senderEmail());
        for(String keyword:plan.topicKeywords())parts.add("\""+keyword+"\"");
        if(plan.startDateInclusive()!=null) {
            var zone=ZoneId.of("Asia/Kolkata");
            long inclusiveStart=plan.startDateInclusive().atStartOfDay(zone).toEpochSecond();
            long exclusiveEnd=plan.endDateInclusive().plusDays(1).atStartOfDay(zone).toEpochSecond();
            // Gmail's after/before operators are exclusive. Subtract one second at the
            // start boundary and use the next local midnight to include the whole end day.
            parts.add("after:"+(inclusiveStart-1));
            parts.add("before:"+exclusiveEnd);
        }
        return String.join(" ",parts);
    }

    /** Starts one explicit, bounded history sync session after verifying the configured mailbox identity. */
    public SyncSession beginHistorySync() {
        sandbox.requireGmail();
        GmailRequestBudget budget=new GmailRequestBudget();
        String token=oauth.accessToken();
        JsonNode profile=transport.getQuestion(BASE+"/profile",token,budget);
        String address=profile==null?"":profile.path("emailAddress").asText("");
        String historyId=profile==null?"":profile.path("historyId").asText("");
        if(address.isBlank() || !historyId(historyId))throw syncResponseFailure();
        sandbox.checkGmailAccount(address);
        return new SyncSession(address,historyId,token,budget);
    }

    public final class SyncSession {
        private final String address,profileHistoryId,token;
        private final GmailRequestBudget budget;
        private int messagesFetched;
        private final int[] attachmentFetches={0},mimePartsVisited={0};
        private SyncSession(String address,String profileHistoryId,String token,GmailRequestBudget budget) {
            this.address=address;this.profileHistoryId=profileHistoryId;this.token=token;this.budget=budget;
        }
        public String address(){return address;}
        public String profileHistoryId(){return profileHistoryId;}
        public int attemptsUsed(){return budget.attemptsUsed();}

        /** Reads one history page only. A continuation token fails closed without fetching another page. */
        public HistoryChanges changesSince(String startHistoryId) {
            if(!historyId(startHistoryId))throw new IllegalArgumentException("Invalid Gmail history cursor");
            String url=BASE+"/history?startHistoryId="+enc(startHistoryId)+"&maxResults=100"+
                "&historyTypes=messageAdded&historyTypes=messageDeleted&historyTypes=labelAdded&historyTypes=labelRemoved";
            final JsonNode result;
            try { result=transport.getQuestion(url,token,budget); }
            catch(GmailFailure failure) {
                if(failure.status()==404)throw new GmailFailure(503,"Gmail sync history cursor expired; no changes were committed","UNSPECIFIED",
                    GmailFailure.DiagnosticCategory.GMAIL_HISTORY_CURSOR_EXPIRED);
                throw failure;
            }
            if(result==null || (result.has("history") && !result.path("history").isArray()))throw syncResponseFailure();
            if(!result.path("nextPageToken").asText("").isBlank())
                throw new GmailFailure(503,"Gmail sync history exceeds the one-page processing limit; no changes were committed","UNSPECIFIED",
                    GmailFailure.DiagnosticCategory.GMAIL_SYNC_HISTORY_PAGE_LIMIT_EXCEEDED);
            String end=result.path("historyId").asText("");
            if(!historyId(end))throw syncResponseFailure();
            LinkedHashMap<String,Boolean> changes=new LinkedHashMap<>();
            JsonNode history=result.path("history");
            if(history.isArray())for(JsonNode event:history) {
                historyMessages(event.path("messagesAdded"),true,changes);
                historyLabelChanges(event.path("labelsAdded"),changes);
                historyMessages(event.path("labelsRemoved"),true,changes);
                historyMessages(event.path("messagesDeleted"),false,changes);
                if(changes.size()>SYNC_MAX_CHANGED_MESSAGES)
                    throw new GmailFailure(503,"Gmail sync change count exceeds its bounded processing limit; no changes were committed","UNSPECIFIED",
                        GmailFailure.DiagnosticCategory.GMAIL_SYNC_CHANGE_LIMIT_EXCEEDED);
            }
            List<String> fetch=new ArrayList<>(),remove=new ArrayList<>();
            changes.forEach((id,keep)->{if(keep)fetch.add(id);else remove.add(id);});
            return new HistoryChanges(end,List.copyOf(fetch),List.copyOf(remove));
        }

        /** Fetches current message content only after the caller has checked its deletion tombstone. */
        public JsonNode readCurrentMessage(String messageId) {
            if(messageId==null || !messageId.matches("[A-Za-z0-9_-]{1,200}") || ++messagesFetched>SYNC_MAX_CHANGED_MESSAGES)
                throw new GmailFailure(503,"Gmail sync message processing limit exceeded; no changes were committed","UNSPECIFIED",
                    GmailFailure.DiagnosticCategory.GMAIL_SYNC_CHANGE_LIMIT_EXCEEDED);
            JsonNode message=transport.getQuestion(BASE+"/messages/"+enc(messageId)+"?format=full",token,budget);
            if(message==null || !messageId.equals(message.path("id").asText()) ||
                    !message.path("threadId").asText("").matches("[A-Za-z0-9_-]{1,200}") || !message.path("payload").isObject())
                throw syncResponseFailure();
            hydrateTextBounded(message.path("payload"),messageId,token,attachmentFetches,mimePartsVisited,budget,false);
            return message;
        }
    }

    private static void historyMessages(JsonNode entries,boolean changed,Map<String,Boolean> changes) {
        if(entries.isMissingNode())return;
        if(!entries.isArray())throw syncResponseFailure();
        for(JsonNode entry:entries) {
            addHistoryChange(entry,changed,changes);
        }
    }
    private static void historyLabelChanges(JsonNode entries,Map<String,Boolean> changes) {
        if(entries.isMissingNode())return;
        if(!entries.isArray())throw syncResponseFailure();
        for(JsonNode entry:entries) {
            JsonNode labels=entry.path("labelIds");
            if(!labels.isArray())throw syncResponseFailure();
            boolean trashed=false;
            for(JsonNode label:labels) {
                if(!label.isTextual() || label.asText().isBlank())throw syncResponseFailure();
                if("TRASH".equals(label.asText()))trashed=true;
            }
            // Trash is a local deletion signal. It takes precedence over ordinary updates
            // for the same immutable message ID and never requires fetching message content.
            addHistoryChange(entry,!trashed,changes);
        }
    }
    private static void addHistoryChange(JsonNode entry,boolean changed,Map<String,Boolean> changes) {
        String id=entry.path("message").path("id").asText("");
        if(!id.matches("[A-Za-z0-9_-]{1,200}"))throw syncResponseFailure();
        // A provider deletion is terminal for this immutable message ID within one batch.
        changes.merge(id,changed,(prior,next)->prior && next);
        if(changes.size()>SYNC_MAX_CHANGED_MESSAGES)
            throw new GmailFailure(503,"Gmail sync change count exceeds its bounded processing limit; no changes were committed","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_SYNC_CHANGE_LIMIT_EXCEEDED);
    }
    private static boolean historyId(String value){return value!=null && value.matches("[0-9]{1,128}");}
    private static GmailFailure syncResponseFailure(){return new GmailFailure(502,"Invalid Gmail sync provider response","UNSPECIFIED",
        GmailFailure.DiagnosticCategory.GMAIL_SYNC_PROVIDER_RESPONSE_INVALID);}
    public record HistoryChanges(String historyId,List<String> changedMessageIds,List<String> removedMessageIds) {
        public HistoryChanges { changedMessageIds=List.copyOf(changedMessageIds);removedMessageIds=List.copyOf(removedMessageIds); }
    }
    private enum HydrationTransport { LEGACY, DIRECT_QUESTION, DEADLINE_AWARE }
    private void hydrateTextBounded(JsonNode part,String messageId,String token,int[] fetches,int[] visited,GmailRequestBudget budget,boolean directQuestion) {
        hydrateTextBounded(part,messageId,token,fetches,visited,budget,directQuestion?HydrationTransport.DIRECT_QUESTION:HydrationTransport.LEGACY);
    }
    private void hydrateTextBounded(JsonNode part,String messageId,String token,int[] fetches,int[] visited,GmailRequestBudget budget,HydrationTransport mode) {
        budget.ensureWithinDeadline();
        if(++visited[0]>QUESTION_MAX_MIME_PARTS)throw new GmailFailure(503,"Gmail question retrieval exceeded its work limit","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_MIME_PART_LIMIT_EXCEEDED);
        String mime=part.path("mimeType").asText();String attachmentId=part.path("body").path("attachmentId").asText("");
        if(part.path("filename").asText("").isBlank() && (mime.equals("text/plain") || mime.equals("text/html")) &&
                !attachmentId.isBlank() && part.path("body").path("data").asText("").isBlank()) {
            if(++fetches[0]>QUESTION_MAX_ATTACHMENT_FETCHES)throw new GmailFailure(503,"Gmail question retrieval exceeded its work limit","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_TEXT_ATTACHMENT_LIMIT_EXCEEDED);
            String attachmentPath="/messages/"+enc(messageId)+"/attachments/"+enc(attachmentId);
            JsonNode body=mode==HydrationTransport.DEADLINE_AWARE
                ? viewerTransport.get(viewerTransport.gmailUri(attachmentPath),token,budget,GmailRequestBudget.Operation.ATTACHMENT)
                : mode==HydrationTransport.DIRECT_QUESTION
                    ? transport.getQuestionOnce(BASE+attachmentPath,token,budget,GmailRequestBudget.Operation.ATTACHMENT)
                    : transport.getQuestion(BASE+attachmentPath,token,budget);
            budget.ensureWithinDeadline();
            if(body==null || !body.hasNonNull("data"))throw new GmailFailure(502,"Gmail text body unavailable");
            ((com.fasterxml.jackson.databind.node.ObjectNode)part.path("body")).put("data",body.path("data").asText());
        }
        for(JsonNode child:part.path("parts"))hydrateTextBounded(child,messageId,token,fetches,visited,budget,mode);
    }
    private void hydrateText(JsonNode part,String messageId,String token) {
        String mime=part.path("mimeType").asText("");
        String attachmentId=part.path("body").path("attachmentId").asText("");
        if (part.path("filename").asText("").isBlank() && (mime.equals("text/plain") || mime.equals("text/html")) &&
                !attachmentId.isBlank() && part.path("body").path("data").asText("").isBlank()) {
            JsonNode body=transport.get(BASE+"/messages/"+enc(messageId)+"/attachments/"+enc(attachmentId),token);
            if (body==null || !body.hasNonNull("data")) throw new GmailFailure(502,"Gmail text body unavailable");
            ((com.fasterxml.jackson.databind.node.ObjectNode)part.path("body")).put("data",body.path("data").asText());
        }
        for (JsonNode child:part.path("parts")) hydrateText(child,messageId,token);
    }
    private String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    public record Mailbox(String address,List<JsonNode> threads,boolean partialResults,GmailRetrievalMetrics metrics) {
        /** Export and legacy callers represent a complete mailbox result unless explicitly marked otherwise. */
        public Mailbox(String address,List<JsonNode> threads) { this(address,threads,false,null); }
        public Mailbox(String address,List<JsonNode> threads,boolean partialResults) { this(address,threads,partialResults,null); }
    }
}
