package br.com.redesurftank.havalcamera;

/** Keeps live command authorization and session ordering ahead of journal mutation. */
final class LiveSessionPolicy {
    private String activeSessionId;

    void requireStartAllowed(ServiceStateReducer.State state) {
        if (!state.remoteLive) throw new IllegalStateException("Transmissão remota desativada");
    }

    void confirmStarted(String sessionId, ServiceStateReducer.State state) {
        requireSessionId(sessionId);
        if (state.mode != ServiceStateReducer.Mode.LIVE_ONLY
                && state.mode != ServiceStateReducer.Mode.RECORDING_LIVE) {
            throw new IllegalStateException("Visualizador ao vivo não confirmado");
        }
        activeSessionId = sessionId;
    }

    void requireMatchingStop(String sessionId) {
        requireSessionId(sessionId);
        if (activeSessionId != null && !activeSessionId.equals(sessionId)) {
            throw new IllegalArgumentException("Sessão ao vivo obsoleta");
        }
    }

    void clear() { activeSessionId = null; }
    String activeSessionId() { return activeSessionId; }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("ID de sessão ao vivo inválido");
        }
    }
}
