package uk.blazecraft.novabroadcast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the active MPSD member created by this process. A shutdown hook covers
 * SIGINT/SIGTERM/container stops; close() covers normal unwinding. Cleanup is
 * one-shot so both paths may race safely.
 */
final class MpsdSessionLease implements AutoCloseable {
    private final SessionDirectoryClient sessions;
    private final AppConfig config;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Thread shutdownHook;

    MpsdSessionLease(SessionDirectoryClient sessions, AppConfig config) {
        this.sessions = sessions;
        this.config = config;
        this.shutdownHook = new Thread(() -> cleanup("JVM shutdown"), "novabroadcast-mpsd-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public void close() {
        cleanup("normal shutdown");
        try {
            if (Thread.currentThread() != shutdownHook) Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown has already started; the hook is either running or
            // about to run. AtomicBoolean prevents duplicate MPSD leave calls.
        }
    }

    private void cleanup(String reason) {
        if (!active.compareAndSet(true, false)) return;
        try {
            System.out.println("[Session] Releasing published MPSD membership during " + reason + "...");
            sessions.leavePublishedSession(config);
        } catch (Exception e) {
            System.err.println("[Session] MPSD shutdown cleanup failed: " + e.getMessage());
            if (Boolean.getBoolean("novabroadcast.debug")) e.printStackTrace();
        }
    }
}
