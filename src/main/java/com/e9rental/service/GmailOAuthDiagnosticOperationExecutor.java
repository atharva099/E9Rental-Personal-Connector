package com.e9rental.service;

import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Isolated one-worker boundary for the separately approved OAuth profile diagnostic. */
@Component
public final class GmailOAuthDiagnosticOperationExecutor implements AutoCloseable {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),daemonFactory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadline=new ScheduledThreadPoolExecutor(1,daemonFactory());
    public GmailOAuthDiagnosticOperationExecutor(){deadline.setRemoveOnCancelPolicy(true);}

    public <T> T execute(GmailRequestBudget budget,Callable<T> operation) {
        return execute(budget,operation,null,null);
    }

    /** Diagnostic timeout returns an explicit partial outcome while cancelling later work. */
    public GmailOAuthDiagnosticResult executeDiagnostic(GmailRequestBudget budget,
            GmailOAuthDiagnosticExecutionState state,Callable<GmailOAuthDiagnosticResult> operation) {
        return execute(budget,operation,state::cancel,state::timeoutResult);
    }

    private <T> T execute(GmailRequestBudget budget,Callable<T> operation,Runnable cancelOperation,Supplier<T> timeoutResult) {
        AtomicBoolean terminal=new AtomicBoolean();AtomicBoolean timedOut=new AtomicBoolean();
        AtomicReference<Thread> running=new AtomicReference<>();AtomicReference<T> value=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch completed=new CountDownLatch(1);
        Runnable task=()->{
            running.set(Thread.currentThread());
            try {if(terminal.get())return;budget.ensureWithinDeadline();T result=operation.call();
                if(terminal.compareAndSet(false,true))value.set(result);
            } catch(Throwable error) {
                if(expired(budget))timeout(terminal,timedOut,running.get(),completed,cancelOperation);
                else if(terminal.compareAndSet(false,true))failure.set(error);
            } finally {running.set(null);completed.countDown();}
        };
        final long remaining;
        try {remaining=budget.remainingNanos();}
        catch(RuntimeException expired){throw timeoutFailure();}
        var timer=deadline.schedule(()->timeout(terminal,timedOut,running.get(),completed,cancelOperation),remaining,TimeUnit.NANOSECONDS);
        try {worker.execute(task);} catch(RejectedExecutionException busy){timer.cancel(false);throw busyFailure();}
        try {if(!completed.await(remaining,TimeUnit.NANOSECONDS))timeout(terminal,timedOut,running.get(),completed,cancelOperation);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();timeout(terminal,timedOut,running.get(),completed,cancelOperation);}
        finally {timer.cancel(false);}
        if(timedOut.get())return timeoutResult==null?throwTimeout():timeoutResult.get();
        // A completed diagnostic can carry truthful partial status after its profile request
        // consumed the remaining budget. Do not replace that result with a generic timeout.
        if(timeoutResult==null && expired(budget))throw timeoutFailure();
        Throwable error=failure.get();
        if(error!=null) {if(error instanceof RuntimeException runtime)throw runtime;throw new GmailFailure(503,"Gmail OAuth profile verification failed");}
        if(timeoutResult!=null && value.get()==null) return timeoutResult.get();
        return value.get();
    }

    private static boolean expired(GmailRequestBudget budget){try{budget.ensureWithinDeadline();return false;}catch(RuntimeException failure){return true;}}
    private static void timeout(AtomicBoolean terminal,AtomicBoolean timedOut,Thread worker,CountDownLatch completed,Runnable cancelOperation) {
        if(terminal.compareAndSet(false,true)){
            if(cancelOperation!=null)cancelOperation.run();
            timedOut.set(true);if(worker!=null)worker.interrupt();completed.countDown();
        }
    }
    private static <T> T throwTimeout(){throw timeoutFailure();}
    private static GmailFailure timeoutFailure(){return new GmailFailure(503,"Gmail OAuth profile verification timed out");}
    private static GmailFailure busyFailure(){return new GmailFailure(503,"Gmail OAuth profile verification is busy");}
    private static ThreadFactory daemonFactory(){return task->{Thread thread=new Thread(task,"gmail-oauth-diagnostic");thread.setDaemon(true);return thread;};}
    @Override public void close(){worker.shutdownNow();deadline.shutdownNow();}
}
