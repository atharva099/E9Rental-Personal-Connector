package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Viewer-only bounded worker boundary with an independent local response-cutoff timer. */
@Component
public final class GmailViewerOperationExecutor implements AutoCloseable {
    public static final int WORKERS=2;
    public static final int QUEUED=2;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(WORKERS,WORKERS,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUED),daemonFactory("gmail-viewer-worker"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,daemonFactory("gmail-viewer-deadline"));
    private final Semaphore admitted=new Semaphore(WORKERS+QUEUED);

    public GmailViewerOperationExecutor() {deadlines.setRemoveOnCancelPolicy(true);}

    public int activeWorkers() {return workers.getActiveCount();}
    public int queuedWorkers() {return workers.getQueue().size();}
    public int availableAdmissions() {return admitted.availablePermits();}

    /** Waits only for the remaining local budget; cancellation and worker exit remain best effort. */
    public <T> T execute(GmailRequestBudget budget,Callable<T> operation) {
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
                // A late result is deliberately dropped and never serialized or logged.
            } catch(Throwable error) {
                if(isExpired(budget))timeout(terminal,timedOut,runningThread.get(),completed);
                else if(terminal.compareAndSet(false,true))failure.set(error);
            } finally {
                runningThread.set(null);
                admitted.release();
                completed.countDown();
            }
        };
        long remaining;
        try {remaining=budget.remainingNanos();}
        catch(GmailFailure expired) {admitted.release();throw timeoutFailure();}
        var timer=deadlines.schedule(()->timeout(terminal,timedOut,runningThread.get(),completed),remaining,TimeUnit.NANOSECONDS);
        try {workers.execute(worker);}
        catch(RejectedExecutionException rejected) {
            timer.cancel(false);admitted.release();throw capacityFailure();
        }
        try {
            boolean finished=completed.await(remaining,TimeUnit.NANOSECONDS);
            if(!finished)timeout(terminal,timedOut,runningThread.get(),completed);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();timeout(terminal,timedOut,runningThread.get(),completed);
        } finally {timer.cancel(false);}
        if(timedOut.get() || isExpired(budget))throw timeoutFailure();
        Throwable error=failure.get();
        if(error!=null) {
            if(error instanceof RuntimeException runtime)throw runtime;
            throw new GmailFailure(503,"Gmail message request failed","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        }
        return value.get();
    }

    private static boolean isExpired(GmailRequestBudget budget) {
        try {budget.ensureWithinDeadline();return false;}catch(GmailFailure expired){return true;}
    }
    private static void timeout(AtomicBoolean terminal,AtomicBoolean timedOut,Thread worker,CountDownLatch completed) {
        if(terminal.compareAndSet(false,true)) {
            timedOut.set(true);
            if(worker!=null)worker.interrupt();
            completed.countDown();
        }
    }
    private static GmailFailure timeoutFailure() {
        return new GmailFailure(503,"Gmail message request timed out","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }
    private static GmailFailure capacityFailure() {
        return new GmailFailure(503,"The Gmail viewer is busy; try again later.","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_VIEWER_CAPACITY_EXHAUSTED);
    }
    private static ThreadFactory daemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger ids=new java.util.concurrent.atomic.AtomicInteger();
        return task->{Thread thread=new Thread(task,prefix+"-"+ids.incrementAndGet());thread.setDaemon(true);return thread;};
    }
    @Override public void close() {workers.shutdownNow();deadlines.shutdownNow();}
}
