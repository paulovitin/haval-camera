package br.com.redesurftank.havalcamera;

import java.util.List;

/** Restores retryable reducer state when a synchronous recording start effect fails. */
final class RetryableStartEffect {
    interface Executor {
        void execute(ServiceStateReducer.Effect effect) throws Exception;
    }

    static void apply(ServiceStateReducer reducer, ServiceStateReducer.Event event,
            long failureSequence, Executor executor) throws Exception {
        List<ServiceStateReducer.Effect> effects = reducer.apply(event);
        boolean startsPipeline = effects.stream().anyMatch(effect ->
                effect.type == ServiceStateReducer.EffectType.START_RECORDING
                        || effect.type == ServiceStateReducer.EffectType.START_CAPTURE);
        try {
            for (ServiceStateReducer.Effect effect : effects) executor.execute(effect);
        } catch (Exception failure) {
            if (!startsPipeline) throw failure;
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
