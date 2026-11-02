package br.com.redesurftank.havalcamera;

/** Main-thread flow for an already-confirmed action that must wait for idle capture. */
final class UpdateIdleFlow {
    interface Host {
        boolean isIdle();
        void endViewing() throws Exception;
        void offerEndViewing(Runnable accept, Runnable cancel);
        void waiting(Runnable cancel);
        void schedulePoll(Runnable poll);
        void stopPolling();
        void cancelled(String reason);
    }
    private final Host host;
    UpdateIdleFlow(Host host) { this.host = host; }
    void whenIdle(Runnable action) { if (host.isIdle()) action.run(); }
    void cancel() { }
    boolean isPending() { return false; }
}
