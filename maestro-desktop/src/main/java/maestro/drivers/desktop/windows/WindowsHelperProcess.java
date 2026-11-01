package maestro.drivers.desktop.windows;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Own helper only. Drain before waiting; no raw temporary file or app/descendant kill. */
public final class WindowsHelperProcess {
    private static final long CLEANUP_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private WindowsHelperProcess() { }
    public static final class Result {
        public final int exitCode;
        public final String output;
        private Result(int exitCode, byte[] bytes) {
            this.exitCode = exitCode;
            this.output = new String(bytes, StandardCharsets.UTF_8);
        }
    }
    public static final class OutputLimitException extends IOException {
        public OutputLimitException() { super("Windows helper output exceeded bound"); }
    }
    private static long remaining(long started, long budget) {
        return Math.max(0, budget - (System.nanoTime() - started));
    }
    private static void closeQuietly(Closeable value) {
        try { value.close(); } catch (IOException ignored) { }
    }
    /** Caller created and retains this exact non-App helper Process. Stderr must be merged. */
    public static Result capture(Process process, long timeoutMillis, int maxBytes)
            throws IOException, InterruptedException, TimeoutException {
        if (timeoutMillis <= 0 || maxBytes <= 0 || maxBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid Windows helper limits");
        }
        final long started = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final InputStream pipe = process.getInputStream();
        FutureTask<byte[]> drained = new FutureTask<>(() -> {
            try (InputStream input = pipe) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(8192, maxBytes));
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > maxBytes - bytes.size()) throw new OutputLimitException();
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            } catch (IOException failure) {
                // Only the retained helper is ours to stop, never the target app.
                if (process.isAlive()) process.destroyForcibly();
                throw failure;
            }
        });
        Thread reader = new Thread(drained, "maestro-windows-helper-drain-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        boolean interrupted = false;
        try {
            // No interactive input is part of a hierarchy read.
            closeQuietly(process.getOutputStream());
            long left = remaining(started, budget);
            if (left == 0 || !process.waitFor(left, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Windows helper process deadline");
            }
            left = remaining(started, budget);
            if (left == 0) throw new TimeoutException("Windows helper EOF deadline");
            byte[] bytes;
            try {
                bytes = drained.get(left, TimeUnit.NANOSECONDS);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("Windows helper drain failed");
            }
            return new Result(process.exitValue(), bytes);
        } catch (InterruptedException failure) {
            interrupted = true;
            throw failure;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            drained.cancel(true);
            reader.interrupt();
            // A descendant may retain stdout after the helper exits. Closing a
            // pipe can itself block on some runtimes, so cleanup must not turn
            // the common process+EOF deadline back into an unbounded wait.
            Thread closer = new Thread(() -> {
                closeQuietly(pipe);
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }, "maestro-windows-helper-close-" + process.pid());
            closer.setDaemon(true);
            closer.start();
            long cleanupStarted = System.nanoTime();
            try {
                long left = remaining(cleanupStarted, CLEANUP_NANOS);
                if (left > 0) process.waitFor(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) { interrupted = true; }
            for (Thread thread : new Thread[] {reader, closer}) {
                long left = remaining(cleanupStarted, CLEANUP_NANOS);
                if (left == 0) break;
                try { TimeUnit.NANOSECONDS.timedJoin(thread, left); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
