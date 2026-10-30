package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.e9rental.model.GmailOAuthDiagnosticResult.RefreshFailureCategory;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;

/** Thread-safe, content-free progress state for truthful timeout reporting. */
public final class GmailOAuthDiagnosticExecutionState {
    private boolean cancelled;
    private boolean profileRequestStarted;
    private SideEffectStatus tokenRefresh=SideEffectStatus.NO;
    private SideEffectStatus grantPersistence=SideEffectStatus.NO;
    private RefreshFailureCategory refreshFailureCategory=RefreshFailureCategory.NONE;

    public synchronized void checkActive(GmailRequestBudget budget) {
        if(cancelled)throw timeout();
        budget.ensureWithinDeadline();
        if(cancelled)throw timeout();
    }

    public synchronized void beginRefresh(GmailRequestBudget budget) {
        checkActive(budget);
        tokenRefresh=SideEffectStatus.UNKNOWN;
        refreshFailureCategory=RefreshFailureCategory.UNVERIFIED;
    }

    /** Linearizes provider dispatch against timeout cancellation without holding a lock during I/O. */
    public synchronized void beginProfileRequest(GmailRequestBudget budget) {
        checkActive(budget);
        profileRequestStarted=true;
    }

    public synchronized void refreshSucceeded() { tokenRefresh=SideEffectStatus.YES;refreshFailureCategory=RefreshFailureCategory.NONE; }
    public synchronized void refreshFailed(RefreshFailureCategory category,SideEffectStatus status) {
        refreshFailureCategory=category==null?RefreshFailureCategory.UNVERIFIED:category;
        tokenRefresh=status==null?SideEffectStatus.UNKNOWN:status;
    }

    /** Once storage begins, a later interruption cannot prove whether the write completed. */
    public synchronized void beginGrantPersistence(GmailRequestBudget budget) {
        checkActive(budget);
        grantPersistence=SideEffectStatus.UNKNOWN;
    }

    public synchronized void grantPersistenceSucceeded() { grantPersistence=SideEffectStatus.YES; }

    public synchronized void cancel() { cancelled=true; }
    public synchronized boolean cancelled() { return cancelled; }

    public synchronized GmailOAuthDiagnosticResult timeoutResult() {
        cancelled=true;
        return new GmailOAuthDiagnosticResult("TIMED_OUT",
            profileRequestStarted
                ? "The deadline stopped further work. A provider request already in progress may still complete; UNKNOWN side effects are indeterminate."
                : "The deadline stopped further work. An in-flight provider request or grant write may still complete; UNKNOWN statuses are indeterminate.",
            "UNVERIFIED","UNVERIFIED","UNVERIFIED","UNVERIFIED","UNVERIFIED",tokenRefresh,grantPersistence,
            tokenRefresh==SideEffectStatus.UNKNOWN && refreshFailureCategory==RefreshFailureCategory.UNVERIFIED
                ?RefreshFailureCategory.TIMEOUT:refreshFailureCategory);
    }

    public synchronized SideEffectStatus tokenRefreshStatus() { return tokenRefresh; }
    public synchronized SideEffectStatus grantPersistenceStatus() { return grantPersistence; }
    public synchronized RefreshFailureCategory refreshFailureCategory() { return refreshFailureCategory; }

    private static GmailFailure timeout() {
        return new GmailFailure(503,"Gmail OAuth profile verification timed out","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }
}
