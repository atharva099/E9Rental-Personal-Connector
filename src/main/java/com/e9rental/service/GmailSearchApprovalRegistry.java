package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.connector.GmailSearchPlan;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** In-memory, short-lived, one-use authorizations for one exact live Gmail search plan. */
@Component
public final class GmailSearchApprovalRegistry {
    private static final int MAX_APPROVALS=128;
    private static final Duration TTL=Duration.ofSeconds(60);
    private static final SecureRandom RANDOM=new SecureRandom();
    private record Approval(GmailSearchPlan plan,String principal,String session,String tenant,String account,Instant expiresAt) {}
    private final Clock clock;
    private final Map<String,Approval> approvals=new HashMap<>();
    private final ReentrantLock lock=new ReentrantLock();

    public GmailSearchApprovalRegistry(Clock clock) { this.clock=Objects.requireNonNull(clock); }

    public String issue(GmailSearchPlan plan,GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account) {
        return issue(plan,scope,tenant,account,null);
    }

    public String issue(GmailSearchPlan plan,GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account,
            GmailRequestBudget budget) {
        acquire(budget);
        try {
            if(budget!=null)budget.ensureWithinDeadline();
            purgeExpired();
            if(plan==null || scope==null || blank(tenant) || blank(account))throw invalid();
            if(approvals.size()>=MAX_APPROVALS)throw unavailable();
            String token=randomToken();
            approvals.put(token,new Approval(plan,scope.principal(),scope.clientSession(),tenant,account,clock.instant().plus(TTL)));
            if(budget!=null)try {budget.ensureWithinDeadline();} catch(RuntimeException expired) {approvals.remove(token);throw expired;}
            return token;
        } finally {lock.unlock();}
    }

    /** Consumes authorization atomically before any Gmail operation begins. */
    public GmailSearchPlan consume(String token,GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account) {
        return consume(token,scope,tenant,account,null);
    }

    public GmailSearchPlan consume(String token,GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account,
            GmailRequestBudget budget) {
        acquire(budget);
        try {
            if(budget!=null)budget.ensureWithinDeadline();
            purgeExpired();
            Approval approval=approvals.get(token);
            if(approval==null || scope==null || !approval.principal().equals(scope.principal()) ||
                    !approval.session().equals(scope.clientSession()) || !approval.tenant().equals(tenant) ||
                    !approval.account().equals(account))throw invalid();
            if(budget!=null)budget.ensureWithinDeadline();
            approvals.remove(token);
            return approval.plan();
        } finally {lock.unlock();}
    }

    public int sizeForTests() { lock.lock();try {purgeExpired();return approvals.size();} finally {lock.unlock();} }
    private void acquire(GmailRequestBudget budget) {
        if(budget==null){lock.lock();return;}
        try {
            if(!lock.tryLock(budget.remainingNanos(),TimeUnit.NANOSECONDS))throw timeout();
            budget.ensureWithinDeadline();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw timeout();}
        catch(RuntimeException failure) {if(lock.isHeldByCurrentThread())lock.unlock();throw failure;}
    }
    private void purgeExpired() { Instant now=clock.instant();approvals.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt())); }
    private static String randomToken() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static GmailFailure invalid() { return new GmailFailure(404,"Gmail search approval is invalid or expired"); }
    private static GmailFailure unavailable() { return new GmailFailure(503,"Gmail search approval capacity is unavailable"); }
    private static GmailFailure timeout() {return new GmailFailure(503,"Live Gmail search timed out before completion","UNSPECIFIED",
        GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT);}
}
