package com.e9rental.service.qa;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailGateway;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.SearchRecord;
import com.e9rental.model.GmailOutput;
import com.e9rental.service.GmailAnalyzer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded question-triggered Gmail lookup. Results are mapped in memory and never persisted here. */
@Service
public class GmailQuestionRetrievalService implements GmailQuestionRetriever {
    private final GmailGateway gateway;
    private final GmailAnalyzer analyzer;
    private final GoogleSandboxPolicy sandbox;
    private final Clock clock;
    private final String tenant;
    public GmailQuestionRetrievalService(GmailGateway gateway,GmailAnalyzer analyzer,GoogleSandboxPolicy sandbox,Clock clock,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant) {
        this.gateway=gateway;this.analyzer=analyzer;this.sandbox=sandbox;this.clock=clock;this.tenant=tenant;
    }

    @Override public RetrievalResult retrieve(QuestionInterpreter.Plan plan) {
        return retrieve(buildSearchPlan(plan));
    }

    /** Shared transient provider-to-evidence mapping for direct search and the existing Ask path. */
    public RetrievalResult retrieve(GmailSearchPlan searchPlan) {
        return retrieve(searchPlan,null);
    }

    /** Live Gmail Search uses its own deadline-aware transport; the existing Ask path remains unchanged. */
    public RetrievalResult retrieve(GmailSearchPlan searchPlan,GmailRequestBudget budget) {
        if(searchPlan==null)throw new GmailFailure(400,"Invalid typed Gmail search");
        GmailGateway.Mailbox mailbox;
        try {
            sandbox.requireGmail();
            if(budget!=null)budget.ensureWithinDeadline();
            mailbox=budget==null?gateway.searchQuestion(searchPlan):gateway.searchDirect(searchPlan,budget);
            if(budget!=null)budget.ensureWithinDeadline();
        } catch(GmailFailure e) {
            if(GoogleSandboxPolicy.DENIAL.equals(e.reason()))throw e;
            if(budget!=null && deadlineExpired(budget))throw new GmailFailure(503,"Live Gmail search timed out before completion","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT);
            var category=e.diagnosticCategory();
            if(category==GmailFailure.DiagnosticCategory.NONE)
                category=e.status()==401?GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE:
                    GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE;
            throw new GmailFailure(e.status(),"Gmail question retrieval failed",e.safeReason(),category);
        } catch(RuntimeException e) {
            throw new GmailFailure(503,"Gmail question retrieval failed","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        }
        long retrievedCount=mailbox.threads().stream().mapToLong(thread->thread.path("messages").size()).sum();
        if(retrievedCount>searchPlan.maxMessages() || retrievedCount>GmailGateway.QUESTION_MAX_MESSAGES)
            throw new GmailFailure(502,"Gmail search exceeded its result limit","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_MESSAGE_LIMIT_EXCEEDED);
        // Defense in depth: GmailGateway checks the profile before content retrieval; check its result again.
        sandbox.checkGmailAccount(mailbox.address());
        String mode=gateway.dataMode();
        if(!Set.of("LIVE","SYNTHETIC").contains(mode))throw new GmailFailure(503,"Gmail provider mode is invalid","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        var output=analyzer.analyze(mailbox.threads(),mailbox.address(),tenant,sandbox.gmailRecordAccount(),
            LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC),clock.instant(),mode);
        if(budget!=null)budget.ensureWithinDeadline();
        List<SearchRecord> result=new ArrayList<>();
        for(var message:output.messages()) {
            if(budget!=null)budget.ensureWithinDeadline();
            var source=message.source();var citation=message.citation();
            SearchRecord record=new SearchRecord(source,mode,message.classification(),
                Objects.toString(source.metadata().get("from"),null),
                Map.of("classification_reasons",message.classificationReasons(),"attachments",message.attachments(),
                    "inbound",message.inbound(),"unanswered_five_days",message.unansweredFiveDays(),
                    "reference_date",output.referenceDate().toString(),"coverage","BOUNDED_QUESTION_RESULTS"),
                new SearchRecord.Citation(citation.documentId(),citation.sourceVersion(),citation.sourceType(),
                    citation.locator(),citation.sourceUrl()));
            if(!tenant.equals(source.tenantId()) || !"GMAIL".equals(source.sourceType()))GoogleSandboxPolicy.deny();
            sandbox.checkRecord(record);
            result.add(record);
        }
        if(searchPlan.queryKind()==GmailSearchPlan.QueryKind.DATE || searchPlan.queryKind()==GmailSearchPlan.QueryKind.RECENT ||
                searchPlan.queryKind()==GmailSearchPlan.QueryKind.DIRECT_SEARCH) {
            result.sort(Comparator.comparing((SearchRecord r)->r.source().occurredAt(),Comparator.nullsLast(Comparator.reverseOrder())));
            if(searchPlan.queryKind()==GmailSearchPlan.QueryKind.RECENT && result.size()>searchPlan.maxMessages())
                result=new ArrayList<>(result.subList(0,searchPlan.maxMessages()));
        }
        if(budget!=null)budget.ensureWithinDeadline();
        return new RetrievalResult(result,mailbox.partialResults(),mailbox.metrics());
    }

    private static boolean deadlineExpired(GmailRequestBudget budget) {
        try {budget.ensureWithinDeadline();return false;}catch(GmailFailure expired) {return true;}
    }

    /** Produces a typed provider query; natural-language text and Gmail operators are never forwarded. */
    public static GmailSearchPlan buildSearchPlan(QuestionInterpreter.Plan plan) {
        if(plan==null || plan.unsupported() || !plan.browse() || !plan.sources().equals(Set.of("GMAIL")) ||
                plan.classification()!=null || plan.commitments() || plan.amounts() || plan.dateAnswer())
            throw new GmailFailure(400,"Live Gmail search supports a simple Gmail topic query only");
        if(plan.gmailSearch()!=null) {
            var typed=plan.gmailSearch();
            if(typed.queryKind()==GmailSearchPlan.QueryKind.DATE) {
                var zone=java.time.ZoneId.of("Asia/Kolkata");
                if(plan.terms().isEmpty() && plan.from()!=null && plan.to()!=null && typed.startDateInclusive()!=null && typed.startDateInclusive().equals(typed.endDateInclusive()) &&
                        plan.from().equals(typed.startDateInclusive().atStartOfDay(zone).toInstant()) &&
                        plan.to().equals(typed.endDateInclusive().plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)))return typed;
            }
            if(typed.queryKind()==GmailSearchPlan.QueryKind.RECENT && plan.terms().isEmpty() && plan.from()==null && plan.to()==null)return typed;
            if((typed.queryKind()==GmailSearchPlan.QueryKind.TOPIC || typed.queryKind()==GmailSearchPlan.QueryKind.TYPED_SEARCH) &&
                    plan.terms().equals(typed.topicKeywords()) && plan.from()!=null && plan.to()!=null && plan.from().isBefore(plan.to()))return typed;
            throw new GmailFailure(400,"Invalid typed Gmail search");
        }
        if(plan.terms().isEmpty() || plan.terms().size()>3 || plan.from()!=null || plan.to()!=null)
            throw new GmailFailure(400,"Live Gmail search supports a topic or exact sender/date query");
        try { return new GmailSearchPlan(null,plan.terms(),null,null,GmailSearchPlan.MailScope.REGULAR_MAIL); }
        catch(IllegalArgumentException invalid) { throw new GmailFailure(400,"Live Gmail search supports simple topic words only"); }
    }

    /** Compatibility helper used by focused tests; production submits the validated plan to GmailGateway. */
    public static String buildQuery(QuestionInterpreter.Plan plan) {
        return GmailGateway.buildQuery(buildSearchPlan(plan));
    }
}
