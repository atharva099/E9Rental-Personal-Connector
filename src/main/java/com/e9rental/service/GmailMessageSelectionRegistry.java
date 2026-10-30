package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** Short-lived, in-memory capabilities tying a live result to one authorized Gmail detail read. */
@Component
public final class GmailMessageSelectionRegistry {
    private static final int MAX_SELECTIONS=256;
    private static final Duration SELECTION_TTL=Duration.ofMinutes(5);
    private static final Duration APPROVAL_TTL=Duration.ofSeconds(60);
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Clock clock;
    private final Map<String,Selection> selections=new HashMap<>();
    private final Map<String,Approval> approvals=new HashMap<>();
    private final ReentrantLock stateLock=new ReentrantLock();

    public GmailMessageSelectionRegistry(Clock clock) { this.clock=clock; }

    public record RequestScope(String principal,String clientSession) {
        public RequestScope {
            if(principal==null || !principal.matches("[0-9a-f]{64}") || clientSession==null ||
                    !clientSession.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("Invalid local viewer scope");
        }
    }

    public record SelectedMessage(String messageId,String tenant,String account) {}
    private static final class Selection {
        final String messageId,tenant,account,principal,clientSession;
        final Instant expiresAt;
        boolean approvalIssued;
        Selection(String id,String tenant,String account,RequestScope scope,Instant expiresAt) {
            this.messageId=id;this.tenant=tenant;this.account=account;this.principal=scope.principal();
            this.clientSession=scope.clientSession();this.expiresAt=expiresAt;
        }
    }
    private record Approval(String selectionId,String principal,String clientSession,Instant expiresAt) {}

    public String register(String messageId,String tenant,String account,RequestScope scope) {
        return registerAll(Collections.singletonList(messageId),tenant,account,scope).get(0);
    }

    /** Registers a bounded result page atomically so partial pages cannot leave orphaned selections. */
    public List<String> registerAll(List<String> messageIds,String tenant,String account,RequestScope scope) {
        return registerAll(messageIds,tenant,account,scope,null);
    }

    /** Search-only overload includes selection registration in the shared response deadline. */
    public List<String> registerAll(List<String> messageIds,String tenant,String account,RequestScope scope,
            com.e9rental.connector.GmailRequestBudget budget) {
        acquire(budget);try {
            if(budget!=null)budget.ensureWithinDeadline();
            purgeExpired();
            if(messageIds==null || messageIds.size()>20 || blank(tenant) || blank(account) || scope==null ||
                    messageIds.stream().anyMatch(id->id==null || !id.matches("[A-Za-z0-9_-]{1,200}")) ||
                    new HashSet<>(messageIds).size()!=messageIds.size())throw invalidSelection();
            if(selections.size()+messageIds.size()>MAX_SELECTIONS)throw unavailable();
            Instant expiry=clock.instant().plus(SELECTION_TTL);
            List<String> handles=new ArrayList<>(messageIds.size());
            Map<String,Selection> pending=new LinkedHashMap<>();
            for(String messageId:messageIds) {
                String handle=randomHandle();
                while(selections.containsKey(handle) || pending.containsKey(handle))handle=randomHandle();
                handles.add(handle);
                pending.put(handle,new Selection(messageId,tenant,account,scope,expiry));
            }
            if(budget!=null)budget.ensureWithinDeadline();
            selections.putAll(pending);
            if(budget!=null)try {budget.ensureWithinDeadline();}
                catch(RuntimeException expired) {pending.keySet().forEach(selections::remove);throw expired;}
            return List.copyOf(handles);
        } finally {stateLock.unlock();}
    }

    /** Called only after the user has confirmed this one message in the client. */
    public String approve(String selectionId,RequestScope scope,String tenant,String account) {
        stateLock.lock();try {
        purgeExpired();
        Selection selection=selections.get(selectionId);
        if(!matches(selection,scope,tenant,account) || selection.approvalIssued)throw invalidSelection();
        if(approvals.size()>=MAX_SELECTIONS)throw unavailable();
        String approval=randomHandle();
        selection.approvalIssued=true;
        approvals.put(approval,new Approval(selectionId,scope.principal(),scope.clientSession(),clock.instant().plus(APPROVAL_TTL)));
        return approval;
        } finally {stateLock.unlock();}
    }

    /** Consumes both one-use capabilities before any Gmail content request is made. */
    public SelectedMessage consume(String approvalId,RequestScope scope,String tenant,String account) {
        return consume(approvalId,scope,tenant,account,null);
    }
    public SelectedMessage consume(String approvalId,RequestScope scope,String tenant,String account,com.e9rental.connector.GmailRequestBudget budget) {
        acquire(budget);
        try {
        if(budget!=null)budget.ensureWithinDeadline();
        purgeExpired();
        Approval approval=approvals.get(approvalId);
        if(approval==null || scope==null || !approval.principal().equals(scope.principal()) ||
                !approval.clientSession().equals(scope.clientSession()))throw invalidSelection();
        Selection selection=selections.get(approval.selectionId());
        if(!matches(selection,scope,tenant,account))throw invalidSelection();
        if(budget!=null)budget.ensureWithinDeadline();
        approvals.remove(approvalId);
        selections.remove(approval.selectionId());
        return new SelectedMessage(selection.messageId,selection.tenant,selection.account);
        } finally {stateLock.unlock();}
    }

    private void acquire(com.e9rental.connector.GmailRequestBudget budget) {
        if(budget==null){stateLock.lock();return;}
        try {
            if(!stateLock.tryLock(budget.remainingNanos(),TimeUnit.NANOSECONDS))throw timeout();
            budget.ensureWithinDeadline();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw timeout();}
        catch(RuntimeException failure) {if(stateLock.isHeldByCurrentThread())stateLock.unlock();throw failure;}
    }
    public void clear() {stateLock.lock();try{selections.clear();approvals.clear();}finally{stateLock.unlock();}}
    public int selectionCountForTests() {stateLock.lock();try{purgeExpired();return selections.size();}finally{stateLock.unlock();}}
    public int approvalCountForTests() {stateLock.lock();try{purgeExpired();return approvals.size();}finally{stateLock.unlock();}}

    private boolean matches(Selection selection,RequestScope scope,String tenant,String account) {
        return selection!=null && scope!=null && clock.instant().isBefore(selection.expiresAt) &&
            selection.principal.equals(scope.principal()) && selection.clientSession.equals(scope.clientSession()) &&
            selection.tenant.equals(tenant) && selection.account.equals(account);
    }
    private void purgeExpired() {
        Instant now=clock.instant();
        selections.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt));
        approvals.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt) || !selections.containsKey(e.getValue().selectionId()));
    }
    private static String randomHandle() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static GmailFailure invalidSelection() { return new GmailFailure(404,"Gmail message selection is invalid or expired","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE); }
    private static GmailFailure unavailable() { return new GmailFailure(503,"Gmail message selection capacity is unavailable","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE); }
    private static GmailFailure timeout() {return new GmailFailure(503,"Gmail message request timed out","UNSPECIFIED",GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);}
}
