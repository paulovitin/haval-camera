package br.com.redesurftank.havalcamera;

/** Restores retryable reducer state when a synchronous recording start effect fails. */
final class RetryableStartEffect {
    interface Executor {
        void execute(ServiceStateReducer.Effect effect) throws Exception;
    }

    static void apply(ServiceStateReducer reducer, ServiceStateReducer.Event event,
            long failureSequence, Executor executor) throws Exception {
        try {
            for (ServiceStateReducer.Effect effect : reducer.apply(event)) executor.execute(effect);
        } catch (Exception failure) {
            for (ServiceStateReducer.Effect cleanup : reducer.apply(
                    ServiceStateReducer.Event.pipelineFailed(failureSequence, failure.getMessage()))) {
                try { executor.execute(cleanup); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    private RetryableStartEffect() { }
}
