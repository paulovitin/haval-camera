package br.com.redesurftank.havalcamera;

import java.net.URI;

/** Keeps live URL scope, command authorization and session ordering ahead of journal mutation. */
final class LiveSessionPolicy {
    enum Scope { REMOTE_WSS, LOOPBACK_WS }

    static Scope validate(String url, long expiresAtMs, long nowMs) {
        URI uri;
        try { uri = URI.create(url); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid live URL", invalid); }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() == null || !uri.getRawQuery().matches("session=[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid live URL authority or session query");
        }
        long remainingMs = expiresAtMs - nowMs;
        if (expiresAtMs <= nowMs || remainingMs <= 0 || remainingMs > 30 * 60_000L) {
            throw new IllegalArgumentException("Expired live session");
        }
        if ("wss".equalsIgnoreCase(uri.getScheme())) return Scope.REMOTE_WSS;
        // Match the raw authority so DNS, alternate IP spellings and port normalization cannot widen scope.
        if ("ws".equalsIgnoreCase(uri.getScheme())
                && uri.getRawAuthority().matches("(127\\.0\\.0\\.1|\\[::1\\]):[1-9][0-9]{0,4}")
                && uri.getPort() <= 65535 && uri.getPort() != 80) {
            return Scope.LOOPBACK_WS;
        }
        throw new IllegalArgumentException("Live URL requires WSS or literal loopback WS with a nondefault port");
    }

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
