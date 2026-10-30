package com.e9rental.service.qa;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailMessageViewService;
import com.e9rental.service.GmailPreviewSanitizer;
import com.e9rental.config.GoogleSandboxPolicy;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
@Service
public class QuestionAnswerService {
    private final UnifiedRepository repository;private final QuestionInterpreter interpreter;private final EvidenceAnswerProvider provider;private final BoundedReasoningService reasoning;
    private final GmailQuestionRetriever gmailRetriever;
    private final GmailMessageViewService messageViewService;
    private final GoogleSandboxPolicy sandbox;
    static final Pattern INJECTION=Pattern.compile("(?i)ignore.{0,30}(instruction|rule|prompt)|system prompt|developer message|"+
        "reveal.{0,30}(secret|token|credential)|execute.{0,20}(command|tool)|\\[S\\d+\\]");
    private static final List<String> LIMITATIONS=List.of("Exact recorded excerpts, not an LLM-generated conclusion or independent fact verification.",
        "Bounded retrieval; not a complete inventory. Unsupported arithmetic, comparison and relative-date questions abstain.",
        "Local-index answers may be stale; explicit Gmail requests use a bounded lookup and do not promise full-mailbox coverage.");
    private static final Pattern EXHAUSTIVE_LIVE_QUESTION=Pattern.compile("(?i)\\b(?:all|every|each|everything|entire|complete|comprehensive|exhaustive|full|total|how\\s+many|count|number\\s+of)\\b");
    public QuestionAnswerService(UnifiedRepository repository,QuestionInterpreter interpreter,EvidenceAnswerProvider provider){
        this(repository,interpreter,provider,null,null,null,GoogleSandboxPolicy.denied());
    }
    public QuestionAnswerService(UnifiedRepository repository,QuestionInterpreter interpreter,EvidenceAnswerProvider provider,BoundedReasoningService reasoning){
        this(repository,interpreter,provider,reasoning,null,null,GoogleSandboxPolicy.denied());
    }
    public QuestionAnswerService(UnifiedRepository repository,QuestionInterpreter interpreter,EvidenceAnswerProvider provider,
            BoundedReasoningService reasoning,GmailQuestionRetriever gmailRetriever){
        this(repository,interpreter,provider,reasoning,gmailRetriever,null,GoogleSandboxPolicy.denied());
    }
    public QuestionAnswerService(UnifiedRepository repository,QuestionInterpreter interpreter,EvidenceAnswerProvider provider,
            BoundedReasoningService reasoning,GmailQuestionRetriever gmailRetriever,GmailMessageViewService messageViewService){
        this(repository,interpreter,provider,reasoning,gmailRetriever,messageViewService,GoogleSandboxPolicy.denied());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public QuestionAnswerService(UnifiedRepository repository,QuestionInterpreter interpreter,EvidenceAnswerProvider provider,
            BoundedReasoningService reasoning,GmailQuestionRetriever gmailRetriever,GmailMessageViewService messageViewService,
            GoogleSandboxPolicy sandbox){
        this.repository=repository;this.interpreter=interpreter;this.provider=provider;this.reasoning=reasoning;this.gmailRetriever=gmailRetriever;
        this.messageViewService=messageViewService;this.sandbox=sandbox;
    }
    public QuestionAnswer ask(String question) { return ask(question,false); }
    public QuestionAnswer ask(String question,boolean searchGmailNow) {
        return ask(question,searchGmailNow,null);
    }
    public QuestionAnswer ask(String question,boolean searchGmailNow,GmailMessageSelectionRegistry.RequestScope viewScope) {
        if(searchGmailNow && question!=null && EXHAUSTIVE_LIVE_QUESTION.matcher(question).find())
            throw new GmailFailure(400,"Live Gmail lookup cannot answer exhaustive or total-count questions. Ask about a specific topic; at most 20 matching messages will be examined.");
        var plan=interpreter.interpret(question);
        List<String> sources=plan.sources().isEmpty()?List.of("GMAIL","DRIVE","WHATSAPP"):List.copyOf(plan.sources());
        if(sandbox.personalMode() && !searchGmailNow && sources.contains("GMAIL"))throw new GmailFailure(403,
            "Locally indexed Gmail questions are unavailable in Live Gmail-only Personal mode; use an explicitly authorized live Gmail operation",
            GoogleSandboxPolicy.DENIAL);
        if(!"DETERMINISTIC_EXTRACTIVE".equals(provider.mode()))throw new GmailFailure(503,"External answer providers are not enabled");
        if(searchGmailNow)GmailQuestionRetrievalService.buildSearchPlan(plan);
        else if(reasoning!=null){var answer=reasoning.answer(question,plan);if(answer!=null)return answer;}
        if(plan.unsupported() || (plan.terms().isEmpty() && !plan.browse() && !plan.commitments()))
            return response("INSUFFICIENT_EVIDENCE",List.of(),plan,true);
        // Live retrieval is explicitly requested and Gmail-only. Provider failures propagate safely; never fall back.
        List<SearchRecord> transientGmail=List.of();boolean partialGmailResults=false;GmailRetrievalMetrics gmailMetrics=null;
        if(searchGmailNow){
            if(gmailRetriever==null)throw new GmailFailure(503,"Live Gmail question retrieval is unavailable");
            var retrieval=gmailRetriever.retrieve(plan);
            transientGmail=retrieval.records();partialGmailResults=retrieval.partialResults();gmailMetrics=retrieval.metrics();
            int retrievalLimit=plan.gmailSearch()==null?GmailGateway.QUESTION_MAX_MESSAGES:plan.gmailSearch().maxMessages();
            if(transientGmail.size()>retrievalLimit)throw new GmailFailure(502,"Gmail search exceeded its result limit","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
        }
        if(searchGmailNow && plan.gmailSearch()!=null && plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.RECENT) {
            var recent=transientGmail.stream()
                .filter(QuestionAnswerService::valid)
                .sorted(Comparator.comparing((SearchRecord r)->r.source().occurredAt(),Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(plan.gmailSearch().maxMessages())
                .map(r->{var source=r.source();
                    String selectionId=messageViewService!=null && viewScope!=null?messageViewService.register(r,viewScope):null;
                    return new GmailSearchResult(GmailPreviewSanitizer.label(source.title(),512),
                        GmailPreviewSanitizer.label(r.participant(),512),source.occurredAt(),
                        GmailPreviewSanitizer.preview(source.contentText()),selectionId);
                }).toList();
            return response(recent.isEmpty()?"INSUFFICIENT_EVIDENCE":"EVIDENCE_FOUND",List.of(),plan,
                true,partialGmailResults,gmailMetrics,recent);
        }
        List<EvidenceAnswerProvider.Candidate> candidates=new ArrayList<>();
        Set<String> seen=new HashSet<>();boolean limited=false;
        // Search each topic separately, then require all topics in a single supporting excerpt.
        var terms=plan.terms().isEmpty()?Collections.singletonList((String)null):plan.terms();
        for(String source:sources)for(String term:terms) {
            boolean boundedQuestion=plan.gmailSearch()!=null && (plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.DATE ||
                plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.RECENT);
            int resultLimit=boundedQuestion?plan.gmailSearch().maxMessages():100;
            int queryLimit=resultLimit;
            var rows=searchGmailNow?transientGmail:repository.search(new UnifiedRepository.Query(term,source,plan.classification(),null,null,null,null,plan.from(),plan.to(),queryLimit,0));
            if(rows.size()==queryLimit && queryLimit==100)limited=true;
            // A full local date page may be truncated; disclose bounded coverage without reading a 21st record.
            if(!searchGmailNow && plan.gmailSearch()!=null && plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.DATE && rows.size()==resultLimit)
                limited=true;
            if(plan.gmailSearch()!=null && (plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.RECENT ||
                    plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.DATE))
                rows=rows.stream().sorted(Comparator.comparing((SearchRecord r)->r.source().occurredAt(),Comparator.nullsLast(Comparator.reverseOrder()))).toList();
            for(var r:rows) {
                var s=r.source();
                String key=s.tenantId()+"\u0000"+s.connectorAccountId()+"\u0000"+s.sourceType()+"\u0000"+s.documentId();
                if(!seen.add(key))continue;
                if(!valid(r) || INJECTION.matcher(s.contentText()).find() || INJECTION.matcher(Objects.toString(s.title(),"")).find())continue;
                String content=s.contentText();
                // Bound parsing independently of provider input size; never return a clipped factual sentence.
                if(content.length()>100000){limited=true;continue;}
                var sentences=Pattern.compile("[^\\r\\n]+").matcher(content);
                while(sentences.find()) {
                    int start=sentences.start(),end=sentences.end();
                    while(start<end && Character.isWhitespace(content.charAt(start)))start++;
                    String excerpt=content.substring(start,end);
                    if(excerpt.isBlank() || excerpt.length()>600)continue;
                    String normalized=normalize(Objects.toString(s.title(),"")+" "+excerpt);
                    if(!plan.terms().stream().allMatch(t->contains(normalized,t)))continue;
                    if(plan.commitments() && !Pattern.compile("(?i)\\b(i|we)\\s+(will|shall|commit|promise)\\b|\\b(agreed|committed)\\b").matcher(excerpt).find())continue;
                    if(plan.amounts() && !Pattern.compile("(?i)(?:[$€£₹]|\\b(?:USD|INR|EUR|GBP|Rs)\\.?\\s*)\\s*\\d[\\d,.]*|\\d[\\d,.]*\\s*(?:dollars|rupees|USD|INR|EUR|GBP)\\b").matcher(excerpt).find())continue;
                    if(plan.dateAnswer() && !Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(excerpt).find())continue;
                    candidates.add(new EvidenceAnswerProvider.Candidate(r,start,end));break;
                }
            }
        }
        var selected=provider.select(plan,List.copyOf(candidates));
        // No provider can introduce text/identities/offsets not already authorized by retrieval.
        int evidenceLimit=plan.gmailSearch()!=null && (plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.DATE ||
            plan.gmailSearch().queryKind()==GmailSearchPlan.QueryKind.RECENT)?plan.gmailSearch().maxMessages():8;
        if(selected.size()>evidenceLimit || !candidates.containsAll(selected) || new HashSet<>(selected).size()!=selected.size())
            throw new GmailFailure(503,"Answer evidence validation failed");
        Set<String> supported=new HashSet<>();for(var c:selected)supported.add(c.record().source().sourceType());
        if(selected.isEmpty() || !supported.containsAll(plan.sources()))return response("INSUFFICIENT_EVIDENCE",List.of(),plan,limited,partialGmailResults,gmailMetrics);
        List<QuestionAnswer.Evidence> evidence=new ArrayList<>();
        for(var c:selected) {
            var r=c.record();var s=r.source();var cite=r.citation();String label="S"+(evidence.size()+1);
            String selectionId=searchGmailNow && messageViewService!=null && viewScope!=null ? messageViewService.register(r,viewScope) : null;
            evidence.add(new QuestionAnswer.Evidence(label,s.sourceType(),s.externalId(),s.connectorAccountId(),s.documentId(),
                s.sourceVersion(),cite.locator(),cite.sourceUrl(),r.dataMode(),s.title(),c.start(),c.end(),s.contentText().substring(c.start(),c.end()),selectionId));
        }
        return response("EVIDENCE_FOUND",List.copyOf(evidence),plan,limited || candidates.size()>selected.size(),partialGmailResults,gmailMetrics);
    }
    static boolean valid(SearchRecord r) {
        var s=r.source();var c=r.citation();
        return s.deletedAt()==null && s.contentText()!=null && s.sourceVersion()!=null && !s.sourceVersion().isBlank() && c!=null &&
            Objects.equals(s.contentHash(),GmailAnalyzer.hash(s.contentText())) && Objects.equals(s.documentId(),c.documentId()) &&
            Objects.equals(s.sourceVersion(),c.sourceVersion()) && Objects.equals(s.sourceType(),c.sourceType()) &&
            c.locator()!=null && !c.locator().isBlank();
    }
    private String normalize(String value){return " "+value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ")+" ";}
    private boolean contains(String text,String term){return Pattern.compile("(?U)\\b"+Pattern.quote(term)+"(?:s)?\\b").matcher(text).find();}
    private QuestionAnswer response(String status,List<QuestionAnswer.Evidence> evidence,QuestionInterpreter.Plan p,boolean limited) {
        return response(status,evidence,p,limited,false);
    }
    private QuestionAnswer response(String status,List<QuestionAnswer.Evidence> evidence,QuestionInterpreter.Plan p,boolean limited,boolean partialResults) {
        return response(status,evidence,p,limited,partialResults,null);
    }
    private QuestionAnswer response(String status,List<QuestionAnswer.Evidence> evidence,QuestionInterpreter.Plan p,boolean limited,
            boolean partialResults,GmailRetrievalMetrics gmailMetrics) {
        return response(status,evidence,p,limited,partialResults,gmailMetrics,List.of());
    }
    private QuestionAnswer response(String status,List<QuestionAnswer.Evidence> evidence,QuestionInterpreter.Plan p,boolean limited,
            boolean partialResults,GmailRetrievalMetrics gmailMetrics,List<GmailSearchResult> recentEmails) {
        String answer=!recentEmails.isEmpty()?"Showing up to 10 recent Gmail messages from this bounded request. This is not a complete mailbox listing.":
            evidence.isEmpty()?"The answer cannot be determined from the available records with the supported query rules.":
            "Relevant recorded excerpts (not a complete or independently verified conclusion):\n"+
            String.join("\n",evidence.stream().map(e->"["+e.reference()+"] "+e.excerpt()).toList());
        if(partialResults)answer+="\nPartial Gmail coverage: additional matching emails may exist. This answer uses only the bounded messages retrieved and does not represent complete mailbox coverage. Narrow the topic using up to three specific words to reduce matches.";
        return new QuestionAnswer(status,provider.mode(),false,false,answer,evidence,List.copyOf(p.sources()),p.classification(),
            p.from()==null?null:p.from().toString(),p.to()==null?null:p.to().toString(),limited || partialResults,LIMITATIONS,partialResults,gmailMetrics,recentEmails);
    }
}
