package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Live-search-only bounded execution boundary; it has no waiting queue and is separate from the viewer. */
@Component
public final class GmailSearchOperationExecutor implements AutoCloseable {
    public static final int MAX_CONCURRENT=2;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(MAX_CONCURRENT,MAX_CONCURRENT,0,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),daemonFactory("gmail-search-worker"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,daemonFactory("gmail-search-deadline"));
    private final Semaphore admitted=new Semaphore(MAX_CONCURRENT);

    public GmailSearchOperationExecutor() {deadlines.setRemoveOnCancelPolicy(true);}

    public int activeWorkers() {return workers.getActiveCount();}
    public int availableAdmissions() {return admitted.availablePermits();}

    /** The servlet caller waits only for the remaining budget; stuck workers retain one of two slots. */
    public <T> T execute(GmailRequestBudget budget,Callable<T> operation) {
        if(budget==null || operation==null)throw new IllegalArgumentException("A bounded search operation is required");
        if(!admitted.tryAcquire())throw capacityFailure();
        AtomicBoolean terminal=new AtomicBoolean();
        AtomicBoolean timedOut=new AtomicBoolean();
        AtomicReference<Thread> runningThread=new AtomicReference<>();
        AtomicReference<T> value=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        CountDownLatch completed=new CountDownLatch(1);
        Runnable worker=()->{
            runningThread.set(Thread.currentThread());
            try {
                if(terminal.get())return;
                budget.ensureWithinDeadline();
                T result=operation.call();
                budget.ensureWithinDeadline();
                if(terminal.compareAndSet(false,true))value.set(result);
            } catch(Throwable error) {
                if(isExpired(budget))timeout(terminal,timedOut,runningThread.get(),completed);
                else if(terminal.compareAndSet(false,true))failure.set(error);
            } finally {
                runningThread.set(null);
                admitted.release();
                completed.countDown();
            }
        };
        final long remaining;
        try {remaining=budget.remainingNanos();}
        catch(GmailFailure expired) {admitted.release();throw timeoutFailure();}
        final ScheduledFuture<?> timer;
        try {timer=deadlines.schedule(()->timeout(terminal,timedOut,runningThread.get(),completed),remaining,TimeUnit.NANOSECONDS);}
        catch(RejectedExecutionException unavailable) {admitted.release();throw capacityFailure();}
        try {workers.execute(worker);}
        catch(RejectedExecutionException unavailable) {
            timer.cancel(false);admitted.release();throw capacityFailure();
        }
        try {
            if(!completed.await(remaining,TimeUnit.NANOSECONDS))timeout(terminal,timedOut,runningThread.get(),completed);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();timeout(terminal,timedOut,runningThread.get(),completed);
        } finally {timer.cancel(false);}
        if(timedOut.get() || isExpired(budget))throw timeoutFailure();
        Throwable error=failure.get();
        if(error!=null) {
            if(error instanceof RuntimeException runtime)throw runtime;
            throw new GmailFailure(503,"Gmail search failed","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        }
        return value.get();
    }

    private static boolean isExpired(GmailRequestBudget budget) {
        try {budget.ensureWithinDeadline();return false;}catch(GmailFailure expired) {return true;}
    }
    private static void timeout(AtomicBoolean terminal,AtomicBoolean timedOut,Thread worker,CountDownLatch completed) {
        if(terminal.compareAndSet(false,true)) {
            timedOut.set(true);
            if(worker!=null)worker.interrupt();
            completed.countDown();
        }
    }
    private static GmailFailure timeoutFailure() {
        return new GmailFailure(503,"Gmail search timed out before completion","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT);
    }
    private static GmailFailure capacityFailure() {
        return new GmailFailure(503,"Live Gmail search is busy; try again later.","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_SEARCH_CAPACITY_EXHAUSTED);
    }
    private static ThreadFactory daemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger ids=new java.util.concurrent.atomic.AtomicInteger();
        return task->{Thread thread=new Thread(task,prefix+"-"+ids.incrementAndGet());thread.setDaemon(true);return thread;};
    }
    @Override public void close() {workers.shutdownNow();deadlines.shutdownNow();}
}
