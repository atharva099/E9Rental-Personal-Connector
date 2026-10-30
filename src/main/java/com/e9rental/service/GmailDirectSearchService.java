package com.e9rental.service;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.model.GmailSearchFilters;
import com.e9rental.model.GmailSearchResponse;
import com.e9rental.model.GmailSearchResult;
import com.e9rental.model.SearchRecord;
import com.e9rental.service.qa.GmailQuestionRetrievalService;
import com.e9rental.service.qa.GmailQuestionRetriever.RetrievalResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Direct, bounded Gmail search. Results and viewer selections are held only in memory. */
@Service
public final class GmailDirectSearchService {
    private static final Pattern CONTROL=Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final String PARTIAL_WARNING="More matching emails may exist. This page shows only the first 20 results; refine the filters to narrow the search.";
    private final GmailQuestionRetrievalService retrieval;
    private final GmailSearchApprovalRegistry approvals;
    private final GmailMessageViewService viewer;
    private final GoogleSandboxPolicy sandbox;
    private final String tenant;

    public GmailDirectSearchService(GmailQuestionRetrievalService retrieval,GmailSearchApprovalRegistry approvals,
            GmailMessageViewService viewer,GoogleSandboxPolicy sandbox,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant) {
        this.retrieval=retrieval;this.approvals=approvals;this.viewer=viewer;this.sandbox=sandbox;this.tenant=tenant;
    }

    /** Issues a short-lived authorization only; this method never contacts Google. */
    public String approve(GmailSearchFilters filters,GmailMessageSelectionRegistry.RequestScope scope) {
        return approve(filters,scope,null);
    }

    public String approve(GmailSearchFilters filters,GmailMessageSelectionRegistry.RequestScope scope,GmailRequestBudget budget) {
        if(budget!=null)budget.ensureWithinDeadline();
        sandbox.requireGmail();
        if(scope==null)throw new GmailFailure(400,"Invalid Gmail search request");
        GmailSearchPlan plan=filters.toPlan();
        if(budget!=null)budget.ensureWithinDeadline();
        return approvals.issue(plan,scope,tenant,sandbox.gmailRecordAccount(),budget);
    }

    /** Consumes one query-scoped approval before entering the shared protected Gmail retrieval path. */
    public GmailSearchResponse search(String approvalToken,GmailMessageSelectionRegistry.RequestScope scope) {
        return search(approvalToken,scope,null);
    }

    public GmailSearchResponse search(String approvalToken,GmailMessageSelectionRegistry.RequestScope scope,GmailRequestBudget budget) {
        if(budget!=null)budget.ensureWithinDeadline();
        sandbox.requireGmail();
        if(scope==null)throw new GmailFailure(400,"Invalid Gmail search request");
        GmailSearchPlan plan=approvals.consume(approvalToken,scope,tenant,sandbox.gmailRecordAccount(),budget);
        if(budget!=null)budget.ensureWithinDeadline();
        RetrievalResult found=retrieval.retrieve(plan,budget);
        if(budget!=null)budget.ensureWithinDeadline();
        if(found.records().size()>plan.maxMessages() || found.records().size()>20)
            throw new GmailFailure(502,"Gmail search exceeded its result limit");
        List<String> selectionIds=viewer.registerAll(found.records(),scope,budget);
        List<GmailSearchResult> rows=new ArrayList<>(found.records().size());
        for(int i=0;i<found.records().size();i++) {
            if(budget!=null)budget.ensureWithinDeadline();
            SearchRecord record=found.records().get(i);
            var source=record.source();
            rows.add(new GmailSearchResult(safe(source.title(),512),safe(record.participant(),512),source.occurredAt(),
                excerpt(source.contentText()),selectionIds.get(i)));
        }
        if(budget!=null)budget.ensureWithinDeadline();
        return new GmailSearchResponse(rows,found.partialResults(),found.partialResults()?PARTIAL_WARNING:null);
    }

    private static String safe(String value,int max) {
        if(value==null)return null;
        String clean=CONTROL.matcher(value).replaceAll("").strip();
        return clean.length()>max?clean.substring(0,max):clean;
    }
    private static String excerpt(String content) {
        return GmailPreviewSanitizer.preview(content);
    }
}
