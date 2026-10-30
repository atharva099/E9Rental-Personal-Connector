package com.e9rental;

import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailSearchApprovalRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GmailSearchApprovalRegistryTest {
    private static final String TENANT="practice-local",ACCOUNT="gmail-synthetic-account";
    private static final GmailMessageSelectionRegistry.RequestScope SCOPE=scope("0");
    private static final GmailSearchPlan PLAN=new GmailSearchPlan(null,List.of("invoice"),null,null,
        GmailSearchPlan.MailScope.REGULAR_MAIL,GmailSearchPlan.QueryKind.DIRECT_SEARCH,20);

    @Test void approvalIsOneUseAndReturnsTheExactApprovedPlan() {
        var registry=new GmailSearchApprovalRegistry(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"),ZoneOffset.UTC));
        String token=registry.issue(PLAN,SCOPE,TENANT,ACCOUNT);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(registry.consume(token,SCOPE,TENANT,ACCOUNT)).isEqualTo(PLAN);
        assertThatThrownBy(()->registry.consume(token,SCOPE,TENANT,ACCOUNT)).hasMessageContaining("invalid or expired");
    }

    @Test void approvalIsBoundToPrincipalSessionTenantAndAccount() {
        var registry=new GmailSearchApprovalRegistry(Clock.systemUTC());
        for(var attempt:List.of(
                new Attempt(scope("1"),TENANT,ACCOUNT),new Attempt(SCOPE,"other-tenant",ACCOUNT),
                new Attempt(SCOPE,TENANT,"other-account"),new Attempt(scope("2"),TENANT,ACCOUNT))) {
            String token=registry.issue(PLAN,SCOPE,TENANT,ACCOUNT);
            assertThatThrownBy(()->registry.consume(token,attempt.scope(),attempt.tenant(),attempt.account()))
                .hasMessageContaining("invalid or expired");
        }
    }

    @Test void concurrentConsumptionOfOneApprovalHasExactlyOneWinner() throws Exception {
        var registry=new GmailSearchApprovalRegistry(Clock.systemUTC());
        String token=registry.issue(PLAN,SCOPE,TENANT,ACCOUNT);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts=List.of(
                consumeAsync(workers,registry,token,ready,start),consumeAsync(workers,registry,token,ready,start));
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long successful=0;
            for(Future<Boolean> attempt:attempts)if(attempt.get(2,TimeUnit.SECONDS))successful++;
            assertThat(successful).isEqualTo(1);
            assertThat(registry.sizeForTests()).isZero();
        } finally {
            start.countDown();workers.shutdownNow();
            assertThat(workers.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Future<Boolean> consumeAsync(ExecutorService workers,GmailSearchApprovalRegistry registry,
            String token,CountDownLatch ready,CountDownLatch start) {
        return workers.submit(()->{
            ready.countDown();
            if(!start.await(2,TimeUnit.SECONDS))throw new IllegalStateException("test start gate timed out");
            try { registry.consume(token,SCOPE,TENANT,ACCOUNT);return true; }
            catch(com.e9rental.connector.GmailFailure rejected) { return false; }
        });
    }

    @Test void expiryAndBoundedCapacityFailClosed() {
        var instant=new AtomicReference<>(Instant.parse("2026-10-20T00:00:00Z"));
        Clock clock=new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return instant.get();}
        };
        var registry=new GmailSearchApprovalRegistry(clock);String token=registry.issue(PLAN,SCOPE,TENANT,ACCOUNT);
        instant.set(instant.get().plus(61,ChronoUnit.SECONDS));
        assertThatThrownBy(()->registry.consume(token,SCOPE,TENANT,ACCOUNT)).hasMessageContaining("invalid or expired");
        assertThat(registry.sizeForTests()).isZero();
        for(int i=0;i<128;i++)registry.issue(PLAN,SCOPE,TENANT,ACCOUNT);
        assertThatThrownBy(()->registry.issue(PLAN,SCOPE,TENANT,ACCOUNT)).hasMessageContaining("capacity");
    }

    private record Attempt(GmailMessageSelectionRegistry.RequestScope scope,String tenant,String account) {}
    private static GmailMessageSelectionRegistry.RequestScope scope(String digit) {
        return new GmailMessageSelectionRegistry.RequestScope(digit.repeat(64),
            digit.repeat(8)+"-"+digit.repeat(4)+"-4"+digit.repeat(3)+"-8"+digit.repeat(3)+"-"+digit.repeat(12));
    }
}
