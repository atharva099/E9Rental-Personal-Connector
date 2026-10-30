package com.e9rental;

import com.e9rental.service.GmailMessageSelectionRegistry;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GmailMessageSelectionRegistryTest {
    private static final String PRINCIPAL="a".repeat(64);
    private static final String OTHER_PRINCIPAL="b".repeat(64);
    private static final String SESSION="00000000-0000-4000-8000-000000000001";
    private static final String OTHER_SESSION="00000000-0000-4000-8000-000000000002";
    private static final String TENANT="personal-tenant";
    private static final String ACCOUNT="gmail-account-scope";
    private final MutableClock clock=new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
    private final GmailMessageSelectionRegistry registry=new GmailMessageSelectionRegistry(clock);
    private final GmailMessageSelectionRegistry.RequestScope scope=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);

    @Test void validResultSelectionRequiresFreshApprovalAndIsConsumedOnce() {
        String selected=registry.register("synthetic-message-1",TENANT,ACCOUNT,scope);
        assertThat(selected).doesNotContain("synthetic-message-1");
        String approval=registry.approve(selected,scope,TENANT,ACCOUNT);
        assertThat(approval).isNotEqualTo(selected);
        assertThat(registry.consume(approval,scope,TENANT,ACCOUNT))
            .isEqualTo(new GmailMessageSelectionRegistry.SelectedMessage("synthetic-message-1",TENANT,ACCOUNT));
        assertThatThrownBy(()->registry.consume(approval,scope,TENANT,ACCOUNT)).hasMessageContaining("invalid or expired");
        assertThat(registry.selectionCountForTests()).isZero();assertThat(registry.approvalCountForTests()).isZero();
    }
    @Test void foreignPrincipalSessionTenantOrAccountCannotUseSelection() {
        String selected=registry.register("synthetic-message-2",TENANT,ACCOUNT,scope);
        assertThatThrownBy(()->registry.approve(selected,new GmailMessageSelectionRegistry.RequestScope(OTHER_PRINCIPAL,SESSION),TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.approve(selected,new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,OTHER_SESSION),TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.approve(selected,scope,"other-tenant",ACCOUNT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registry.approve(selected,scope,TENANT,"other-account")).isInstanceOf(RuntimeException.class);
        assertThat(registry.approvalCountForTests()).isZero();
    }
    @Test void unapprovedAndReusedSelectionsFailClosed() {
        String selected=registry.register("synthetic-message-3",TENANT,ACCOUNT,scope);
        assertThatThrownBy(()->registry.consume("missing-approval",scope,TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
        registry.approve(selected,scope,TENANT,ACCOUNT);
        assertThatThrownBy(()->registry.approve(selected,scope,TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
    }
    @Test void expiredSelectionAndApprovalCannotBeUsed() {
        String oldSelection=registry.register("synthetic-message-4",TENANT,ACCOUNT,scope);
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(()->registry.approve(oldSelection,scope,TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
        String selected=registry.register("synthetic-message-5",TENANT,ACCOUNT,scope);
        String approval=registry.approve(selected,scope,TENANT,ACCOUNT);
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(()->registry.consume(approval,scope,TENANT,ACCOUNT)).isInstanceOf(RuntimeException.class);
        assertThat(registry.selectionCountForTests()).isEqualTo(1);assertThat(registry.approvalCountForTests()).isZero();
        clock.advance(Duration.ofMinutes(5));
        assertThat(registry.selectionCountForTests()).isZero();
    }
    @Test void malformedSelectionIdentityIsRejectedBeforeRegistration() {
        assertThatThrownBy(()->registry.register("../other-message",TENANT,ACCOUNT,scope)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new GmailMessageSelectionRegistry.RequestScope("bad-principal","not-a-session"))
            .isInstanceOf(RuntimeException.class);
        assertThat(registry.selectionCountForTests()).isZero();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial){instant=new AtomicReference<>(initial);}
        void advance(Duration duration){instant.updateAndGet(x->x.plus(duration));}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant.get();}
    }
}
