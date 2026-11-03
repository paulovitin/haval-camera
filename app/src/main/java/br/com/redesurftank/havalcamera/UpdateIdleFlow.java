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
    private Runnable pending;
    private boolean waiting;
    private long generation;

    UpdateIdleFlow(Host host) { this.host = host; }

    void whenIdle(Runnable action) {
        long ticket = ++generation;
        host.stopPolling(); waiting = false; pending = action;
        if (host.isIdle()) { complete(); return; }
        host.offerEndViewing(() -> accept(ticket), () -> cancel(ticket, "Atualização cancelada."));
    }
    private void accept(long ticket) {
        if (ticket != generation || pending == null || waiting) return;
        waiting = true;
        try {
            host.endViewing();
        } catch (Exception error) {
            cancel(ticket, "Não foi possível encerrar a visualização; atualização cancelada.");
            return;
        }
        host.waiting(() -> cancel(ticket, "Espera pela atualização cancelada."));
        poll(ticket);
    }
    private void poll(long ticket) {
        if (ticket != generation || pending == null || !waiting) return;
        if (host.isIdle()) complete();
        else host.schedulePoll(() -> poll(ticket));
    }
    private void complete() {
        Runnable action = pending;
        pending = null; waiting = false; generation++;
        host.stopPolling();
        action.run();
    }
    private void cancel(long ticket, String reason) {
        if (ticket != generation || pending == null) return;
        pending = null; waiting = false; generation++;
        host.stopPolling(); host.cancelled(reason);
    }
    void cancel() { cancel(generation, "Atualização cancelada."); }
    boolean isPending() { return pending != null; }
}
