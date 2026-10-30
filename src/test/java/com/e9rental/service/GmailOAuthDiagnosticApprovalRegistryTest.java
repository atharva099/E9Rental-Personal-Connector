package com.e9rental.service;

import static org.assertj.core.api.Assertions.*;

import com.e9rental.connector.GmailSearchPlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GmailOAuthDiagnosticApprovalRegistryTest {
    private static final String PRINCIPAL="a".repeat(64);
    private static final String SESSION="11111111-1111-4111-8111-111111111111";

    @Test void approvalIsShortLivedBoundToSessionAndConsumedOnce() {
        var clock=new MutableClock(Instant.parse("2026-10-22T00:00:00Z"));
        var registry=new GmailOAuthDiagnosticApprovalRegistry(clock);
        var scope=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);
        String approval=registry.issue(scope,"personal-test-tenant","gmail-account-hash");
        assertThat(approval).matches("[A-Za-z0-9_-]{43}");
        assertThatThrownBy(()->registry.consume(approval,new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,
            "22222222-2222-4222-8222-222222222222"),"personal-test-tenant","gmail-account-hash"))
            .isInstanceOf(RuntimeException.class);
        registry.consume(approval,scope,"personal-test-tenant","gmail-account-hash");
        assertThatThrownBy(()->registry.consume(approval,scope,"personal-test-tenant","gmail-account-hash"))
            .isInstanceOf(RuntimeException.class);
        String expiring=registry.issue(scope,"personal-test-tenant","gmail-account-hash");
        clock.advanceSeconds(61);
        assertThatThrownBy(()->registry.consume(expiring,scope,"personal-test-tenant","gmail-account-hash"))
            .isInstanceOf(RuntimeException.class);
        assertThat(registry.sizeForTests()).isZero();
    }

    @Test void anotherOperationApprovalRegistryCannotAuthorizeOAuthDiagnostic() {
        var clock=Clock.systemUTC();var registry=new GmailOAuthDiagnosticApprovalRegistry(clock);
        var scope=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);
        String tenant="personal-test-tenant",account="gmail-account-hash";
        var searchApprovals=new GmailSearchApprovalRegistry(clock);
        String search=searchApprovals.issue(new GmailSearchPlan(null,java.util.List.of("invoice"),null,null,
            GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.DIRECT_SEARCH,20),scope,tenant,account);
        assertThatThrownBy(()->registry.consume(search,scope,tenant,account))
            .isInstanceOf(RuntimeException.class);

        var selections=new GmailMessageSelectionRegistry(clock);
        String selected=selections.register("synthetic-message-id",tenant,account,scope);
        String viewer=selections.approve(selected,scope,tenant,account);
        assertThatThrownBy(()->registry.consume(viewer,scope,tenant,account))
            .isInstanceOf(RuntimeException.class);
    }

    @Test void approvalIsBoundToTenantAndAccount() {
        var registry=new GmailOAuthDiagnosticApprovalRegistry(Clock.systemUTC());
        var scope=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);
        String approval=registry.issue(scope,"personal-tenant-a","account-hash-a");
        assertThatThrownBy(()->registry.consume(approval,scope,"personal-tenant-b","account-hash-a"))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.consume(approval,scope,"personal-tenant-a","account-hash-b"))
            .isInstanceOf(RuntimeException.class);
        registry.consume(approval,scope,"personal-tenant-a","account-hash-a");
    }

    @Test void concurrentConsumersCanUseApprovalOnlyOnce() throws Exception {
        var registry=new GmailOAuthDiagnosticApprovalRegistry(Clock.systemUTC());
        var scope=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);
        String approval=registry.issue(scope,"personal-tenant","account-hash");
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var accepted=new AtomicInteger();
        try(ExecutorService consumers=Executors.newFixedThreadPool(2)) {
            var first=consumers.submit(()->consume(registry,approval,scope,ready,start,accepted));
            var second=consumers.submit(()->consume(registry,approval,scope,ready,start,accepted));
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();start.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(1);
        assertThat(registry.sizeForTests()).isZero();
    }

    private static void consume(GmailOAuthDiagnosticApprovalRegistry registry,String approval,
            GmailMessageSelectionRegistry.RequestScope scope,CountDownLatch ready,CountDownLatch start,AtomicInteger accepted) {
        ready.countDown();try {if(!start.await(2,TimeUnit.SECONDS))throw new AssertionError("start gate timed out");
            registry.consume(approval,scope,"personal-tenant","account-hash");accepted.incrementAndGet();
        } catch(RuntimeException expectedRejectedConsumer) { /* exactly one concurrent consumer is rejected */ }
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now){this.now=now;}
        void advanceSeconds(long seconds){now=now.plusSeconds(seconds);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
