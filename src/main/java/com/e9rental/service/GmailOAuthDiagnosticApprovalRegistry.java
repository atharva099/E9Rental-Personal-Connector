package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Isolated, short-lived, one-use approvals exclusively for Gmail OAuth profile verification. */
@Component
public final class GmailOAuthDiagnosticApprovalRegistry {
    private static final int MAX_APPROVALS=32;
    private static final Duration TTL=Duration.ofSeconds(60);
    private static final SecureRandom RANDOM=new SecureRandom();
    private record Approval(String principal,String session,String tenant,String account,Instant expiresAt) {}
    private final Clock clock;
    private final Map<String,Approval> approvals=new HashMap<>();

    public GmailOAuthDiagnosticApprovalRegistry(Clock clock){this.clock=Objects.requireNonNull(clock);}

    public synchronized String issue(GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account) {
        purgeExpired();
        if(scope==null || blank(tenant) || blank(account))throw invalid();
        if(approvals.size()>=MAX_APPROVALS)throw new GmailFailure(503,"Gmail OAuth diagnostic approval is unavailable");
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        approvals.put(token,new Approval(scope.principal(),scope.clientSession(),tenant,account,clock.instant().plus(TTL)));
        return token;
    }

    /** Atomically consumes the diagnostic-only capability before OAuth or Gmail access. */
    public synchronized void consume(String token,GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account) {
        purgeExpired();
        Approval approval=approvals.get(token);
        if(approval==null || scope==null || !approval.principal().equals(scope.principal()) ||
                !approval.session().equals(scope.clientSession()) || !approval.tenant().equals(tenant) ||
                !approval.account().equals(account))throw invalid();
        approvals.remove(token);
    }

    public synchronized int sizeForTests(){purgeExpired();return approvals.size();}
    private void purgeExpired(){Instant now=clock.instant();approvals.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt()));}
    private static byte[] randomBytes(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return bytes;}
    private static boolean blank(String value){return value==null || value.isBlank();}
    private static GmailFailure invalid(){return new GmailFailure(404,"Gmail OAuth diagnostic approval is invalid or expired");}
}
