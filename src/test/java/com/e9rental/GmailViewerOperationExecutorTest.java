package com.e9rental;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.service.GmailViewerOperationExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GmailViewerOperationExecutorTest {
    @Test void timeoutReturnsSafeFixedFailureAndRetainsWorkerCapacityUntilExit() throws Exception {
        try(var executor=new GmailViewerOperationExecutor()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            long started=System.nanoTime();
            assertThatThrownBy(()->executor.execute(GmailRequestBudget.singleMessageView(Duration.ofMillis(100)),()->{
                entered.countDown();waitFor(release);return "private synthetic message body";
            })).isInstanceOf(GmailFailure.class).hasMessage("Gmail message request timed out")
                .extracting(failure->((GmailFailure)failure).diagnosticCategory())
                .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
            assertThat(entered.await(100,TimeUnit.MILLISECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(1));
            assertThat(executor.availableAdmissions()).isEqualTo(3);
            release.countDown();await(()->executor.availableAdmissions()==4);
        }
    }

    @Test void noProviderOperationStartsAfterTheDeadlineAndLateSuccessIsDiscarded() throws Exception {
        try(var executor=new GmailViewerOperationExecutor()) {
            CountDownLatch firstEntered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger operations=new AtomicInteger();
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(100));
            assertThatThrownBy(()->executor.execute(budget,()->{
                operations.incrementAndGet();firstEntered.countDown();waitFor(release);
                budget.ensureWithinDeadline();operations.incrementAndGet();return "private late response";
            })).isInstanceOf(GmailFailure.class);
            assertThat(firstEntered.await(100,TimeUnit.MILLISECONDS)).isTrue();
            release.countDown();await(()->executor.availableAdmissions()==4);
            assertThat(operations).hasValue(1);
        }
    }

    @Test void executorBoundsActiveAndQueuedViewerWorkAndRejectsOverload() throws Exception {
        try(var executor=new GmailViewerOperationExecutor()) {
            CountDownLatch release=new CountDownLatch(1);
            List<java.util.concurrent.CompletableFuture<Void>> callers=new ArrayList<>();
            for(int index=0;index<4;index++)callers.add(java.util.concurrent.CompletableFuture.runAsync(()->{
                try {executor.execute(GmailRequestBudget.singleMessageView(Duration.ofSeconds(2)),()->{waitFor(release);return "ok";});}
                catch(GmailFailure expected) { /* Timeout when the test releases only after admission is checked. */ }
            }));
            await(()->executor.activeWorkers()==2 && executor.queuedWorkers()==2);
            java.util.concurrent.atomic.AtomicBoolean callerRan=new java.util.concurrent.atomic.AtomicBoolean();
            String callerThread=Thread.currentThread().getName();
            assertThatThrownBy(()->executor.execute(GmailRequestBudget.singleMessageView(Duration.ofSeconds(1)),()->{callerRan.set(true);return callerThread;}))
                .isInstanceOf(GmailFailure.class).hasMessage("The Gmail viewer is busy; try again later.")
                .extracting(failure->((GmailFailure)failure).diagnosticCategory())
                .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_VIEWER_CAPACITY_EXHAUSTED);
            assertThat(callerRan).isFalse();
            release.countDown();
            for(var caller:callers)caller.get(2,TimeUnit.SECONDS);
            await(()->executor.availableAdmissions()==4);
            assertThat(executor.activeWorkers()).isLessThanOrEqualTo(2);
            assertThat(executor.queuedWorkers()).isLessThanOrEqualTo(2);
        }
    }

    private static void waitFor(CountDownLatch latch) {
        boolean complete=false;
        while(!complete)try {complete=latch.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored) { /* emulate a transport that observes cancellation late */ }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
