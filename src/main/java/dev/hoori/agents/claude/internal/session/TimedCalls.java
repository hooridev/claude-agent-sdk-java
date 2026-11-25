package dev.hoori.agents.claude.internal.session;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs blocking {@code SessionStore} calls with a deadline on a virtual thread. */
final class TimedCalls {

    private TimedCalls() {
    }

    /**
     * Calls {@code call} and waits at most {@code timeout}. On timeout the call is interrupted
     * (best-effort: it may still complete later) and {@link TimeoutException} is thrown.
     *
     * @throws Exception whatever {@code call} threw
     */
    static <T> T call(Callable<T> call, Duration timeout, String threadName) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().name(threadName).start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
