package com.e9rental.connector;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** One request-local budget shared by every Gmail REST attempt in a question retrieval. */
public final class GmailRequestBudget {
    public static final int MAX_ATTEMPTS = 12;
    public static final int DIRECT_QUESTION_MAX_ATTEMPTS = 27; // profile + list + 20 messages + 5 text bodies
    public static final int DIRECT_QUESTION_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    public static final int DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final long DIRECT_QUESTION_DEADLINE_NANOS = Duration.ofSeconds(180).toNanos();
    public static final int SINGLE_MESSAGE_VIEW_MAX_ATTEMPTS=2; // profile verification + one message.get
    public static final int SINGLE_MESSAGE_VIEW_MAX_RESPONSE_BYTES=4 * 1024 * 1024;
    public static final Duration SINGLE_MESSAGE_VIEW_DEADLINE=Duration.ofSeconds(18);
    public static final Duration DIRECT_SEARCH_DEADLINE=Duration.ofSeconds(180);
    public static final Duration OAUTH_PROFILE_DIAGNOSTIC_DEADLINE=Duration.ofSeconds(18);
    public static final Duration SEARCH_APPROVAL_DEADLINE=Duration.ofSeconds(5);
    public enum Operation { PROFILE, LIST, MESSAGE, ATTACHMENT, OTHER }
    public record Snapshot(int totalAttempts,int profileRequests,int listRequests,int messageRequests,
            int attachmentRequests,long responseBytes) {}
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicLong responseBytes = new AtomicLong();
    private final EnumMap<Operation,AtomicInteger> operationCounts=new EnumMap<>(Operation.class);
    private final int maxAttempts,maxResponseBytes,maxSingleResponseBytes;
    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    public GmailRequestBudget() { this(MAX_ATTEMPTS,0,0,0,System::nanoTime); }

    private GmailRequestBudget(int maxAttempts,int maxResponseBytes,int maxSingleResponseBytes,long deadlineNanos,LongSupplier nanoTime) {
        this.maxAttempts=maxAttempts;this.maxResponseBytes=maxResponseBytes;
        this.maxSingleResponseBytes=maxSingleResponseBytes;
        this.deadlineNanos=deadlineNanos;
        this.nanoTime=nanoTime;
        for(Operation operation:Operation.values())operationCounts.put(operation,new AtomicInteger());
    }

    public static GmailRequestBudget directQuestion() {
        return directQuestionBudget(DIRECT_QUESTION_DEADLINE_NANOS);
    }

    /** Direct-search deadline starts at controller ingress and is shared through OAuth, Gmail and mapping. */
    public static GmailRequestBudget directSearch() { return directSearch(DIRECT_SEARCH_DEADLINE); }

    /** Injectable shorter search deadlines are used only by synthetic tests. */
    public static GmailRequestBudget directSearch(Duration duration) {
        if(duration==null || duration.isNegative() || duration.isZero() || duration.compareTo(DIRECT_SEARCH_DEADLINE)>0)
            throw new IllegalArgumentException("Invalid Gmail search deadline");
        return directQuestionBudget(duration.toNanos());
    }

    /** Short ingress budget for issuing a search approval; it never authorizes provider access. */
    public static GmailRequestBudget searchApproval() { return operationDeadline(SEARCH_APPROVAL_DEADLINE); }

    /** Injectable short approval deadlines are used only by synthetic HTTP tests. */
    public static GmailRequestBudget searchApproval(Duration duration) {
        if(duration==null || duration.isNegative() || duration.isZero() || duration.compareTo(SEARCH_APPROVAL_DEADLINE)>0)
            throw new IllegalArgumentException("Invalid Gmail approval deadline");
        return operationDeadline(duration);
    }

    public static GmailRequestBudget operationDeadline(Duration duration) {
        if(duration==null || duration.isNegative() || duration.isZero() || duration.compareTo(DIRECT_SEARCH_DEADLINE)>0)
            throw new IllegalArgumentException("Invalid Gmail operation deadline");
        return new GmailRequestBudget(0,0,0,System.nanoTime()+duration.toNanos(),System::nanoTime);
    }

    private static GmailRequestBudget directQuestionBudget(long deadlineNanos) {
        return new GmailRequestBudget(DIRECT_QUESTION_MAX_ATTEMPTS,DIRECT_QUESTION_MAX_RESPONSE_BYTES,DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES,
            System.nanoTime()+deadlineNanos,System::nanoTime);
    }

    /** One profile check and one full-message read; no attachment or retry operations fit this budget. */
    public static GmailRequestBudget singleMessageView() {
        return singleMessageView(SINGLE_MESSAGE_VIEW_DEADLINE);
    }

    /** One bounded Gmail profile request for the separately approved OAuth diagnostic. */
    public static GmailRequestBudget oauthProfileDiagnostic() {
        return new GmailRequestBudget(1,64*1024,64*1024,
            System.nanoTime()+OAUTH_PROFILE_DIAGNOSTIC_DEADLINE.toNanos(),System::nanoTime);
    }

    /** Injectable short deadlines keep timeout tests deterministic and fast. */
    public static GmailRequestBudget singleMessageView(Duration duration) {
        if(duration==null || duration.isNegative() || duration.isZero() || duration.compareTo(SINGLE_MESSAGE_VIEW_DEADLINE)>0)
            throw new IllegalArgumentException("Invalid message-view deadline");
        return new GmailRequestBudget(SINGLE_MESSAGE_VIEW_MAX_ATTEMPTS,SINGLE_MESSAGE_VIEW_MAX_RESPONSE_BYTES,
            DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES,System.nanoTime()+duration.toNanos(),System::nanoTime);
    }

    static GmailRequestBudget singleMessageView(Duration duration,LongSupplier clock) {
        if(duration==null||duration.isNegative()||duration.isZero()||duration.compareTo(SINGLE_MESSAGE_VIEW_DEADLINE)>0||clock==null)
            throw new IllegalArgumentException("Invalid message-view deadline");
        return new GmailRequestBudget(SINGLE_MESSAGE_VIEW_MAX_ATTEMPTS,SINGLE_MESSAGE_VIEW_MAX_RESPONSE_BYTES,
            DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES,clock.getAsLong()+duration.toNanos(),clock);
    }

    /** Reserve an attempt immediately before sending it. Retries must reserve again. */
    public void consumeAttempt() {
        consumeAttempt(Operation.OTHER);
    }

    public void consumeAttempt(Operation operation) {
        if(operation==null)throw new IllegalArgumentException("Gmail REST operation is required");
        ensureWithinDeadline();
        while (true) {
            int used = attempts.get();
            if (used >= maxAttempts) {
                throw new GmailFailure(503, "Gmail question retrieval attempt budget exhausted", "UNSPECIFIED",
                    GmailFailure.DiagnosticCategory.GMAIL_REQUEST_BUDGET_EXHAUSTED);
            }
            if (attempts.compareAndSet(used, used + 1)) {
                operationCounts.get(operation).incrementAndGet();
                return;
            }
        }
    }

    public void ensureWithinDeadline() {
        if(deadlineNanos!=0 && nanoTime.getAsLong()-deadlineNanos>=0)
            throw new GmailFailure(503,"Gmail question retrieval time limit exceeded","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }

    public long remainingNanos() {
        if(deadlineNanos==0)return Long.MAX_VALUE;
        long remaining=deadlineNanos-nanoTime.getAsLong();
        if(remaining<=0)ensureWithinDeadline();
        return remaining;
    }

    public int remainingResponseBytes() {
        if(maxResponseBytes==0)return Integer.MAX_VALUE;
        return Math.max(0,maxResponseBytes-(int)Math.min(Integer.MAX_VALUE,responseBytes.get()));
    }

    public int maxSingleResponseBytes() { return maxSingleResponseBytes; }

    public void recordResponseBytes(int bytes) {
        if(bytes<0)throw new IllegalArgumentException("Invalid response size");
        long total=responseBytes.addAndGet(bytes);
        if(maxResponseBytes>0 && total>maxResponseBytes) {
            responseBytes.addAndGet(-bytes);
            throw responseSizeFailure();
        }
    }

    public static GmailFailure responseSizeFailure() {
        return new GmailFailure(503,"Gmail question retrieval exceeded its response-size limit","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED);
    }

    public int attemptsUsed() {
        return attempts.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(attempts.get(),operationCounts.get(Operation.PROFILE).get(),operationCounts.get(Operation.LIST).get(),
            operationCounts.get(Operation.MESSAGE).get(),operationCounts.get(Operation.ATTACHMENT).get(),responseBytes.get());
    }
}
