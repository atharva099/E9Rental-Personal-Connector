package com.e9rental;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.service.GmailSearchOperationExecutor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GmailSearchOperationExecutorTest {
    @Test void timedOutWorkersRetainBothAdmissionSlotsUntilActualExitAndLateValuesAreDiscarded() throws Exception {
        try(var search=new GmailSearchOperationExecutor()) {
            CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
            ExecutorService callers=Executors.newFixedThreadPool(2);
            try {
                List<Future<GmailFailure.DiagnosticCategory>> requests=List.of(
                    callers.submit(()->executeSlow(search,entered,release)),
                    callers.submit(()->executeSlow(search,entered,release)));
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                for(Future<GmailFailure.DiagnosticCategory> request:requests)
                    assertThat(request.get(2,TimeUnit.SECONDS)).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT);
                assertThat(search.availableAdmissions()).isZero();
                assertThatThrownBy(()->search.execute(GmailRequestBudget.directSearch(Duration.ofSeconds(1)),()->"unexpected"))
                    .isInstanceOf(GmailFailure.class)
                    .extracting(error->((GmailFailure)error).diagnosticCategory())
                    .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_SEARCH_CAPACITY_EXHAUSTED);

                release.countDown();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(search.availableAdmissions()!=GmailSearchOperationExecutor.MAX_CONCURRENT && System.nanoTime()<end)
                    Thread.sleep(5);
                assertThat(search.availableAdmissions()).isEqualTo(GmailSearchOperationExecutor.MAX_CONCURRENT);
            } finally {release.countDown();callers.shutdownNow();assertThat(callers.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        }
    }

    private static GmailFailure.DiagnosticCategory executeSlow(GmailSearchOperationExecutor search,CountDownLatch entered,
            CountDownLatch release) {
        try {
            search.execute(GmailRequestBudget.directSearch(Duration.ofMillis(100)),()->{
                entered.countDown();
                boolean done=false;
                while(!done)try {done=release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored) { /* Hold the synthetic worker until released. */ }
                return "PRIVATE_LATE_SEARCH_RESULT";
            });
            throw new AssertionError("A late search result must never be returned");
        } catch(GmailFailure failure) {return failure.diagnosticCategory();}
    }
}
