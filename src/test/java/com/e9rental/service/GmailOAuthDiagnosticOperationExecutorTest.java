package com.e9rental.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GmailOAuthDiagnosticOperationExecutorTest {
    @Test void timeoutReportsIndeterminateSideEffectsAndPreventsLaterGrantWrite() throws Exception {
        var executor=new GmailOAuthDiagnosticOperationExecutor();
        var caller=Executors.newSingleThreadExecutor();
        var state=new GmailOAuthDiagnosticExecutionState();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
        var wroteGrant=new AtomicBoolean();
        try {
            var resultFuture=caller.submit(()->executor.executeDiagnostic(
                GmailRequestBudget.operationDeadline(Duration.ofMillis(250)),state,()->{
                    state.beginRefresh(GmailRequestBudget.operationDeadline(Duration.ofSeconds(2)));
                    entered.countDown();
                    boolean released=false;
                    while(!released)try {released=release.await(2,TimeUnit.SECONDS);}
                    catch(InterruptedException ignored) { /* emulate an uncooperative provider/library call */ }
                    try {
                        state.beginGrantPersistence(GmailRequestBudget.operationDeadline(Duration.ofSeconds(2)));
                        wroteGrant.set(true);
                    } finally {exited.countDown();}
                    return null;
                }));

            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var result=resultFuture.get(2,TimeUnit.SECONDS);
            assertThat(result.diagnosticStatus()).isEqualTo("TIMED_OUT");
            assertThat(result.tokenRefreshStatus()).isEqualTo(SideEffectStatus.UNKNOWN);
            assertThat(result.encryptedGrantPersistenceStatus()).isEqualTo(SideEffectStatus.NO);
            assertThat(result.statusNote()).contains("may still complete");

            release.countDown();
            assertThat(exited.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(wroteGrant).isFalse();
        } finally {
            release.countDown();caller.shutdownNow();executor.close();
        }
    }
}
