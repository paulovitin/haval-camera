package br.com.redesurftank.havalcamera;

/** Pure mapping from durable journal outcomes to the cross-process result contract. */
final class CommandAcknowledgement {
    private CommandAcknowledgement() { }

    static String statusFor(CommandJournal.Outcome outcome) {
        switch (outcome) {
            case APPLIED: return VideoContract.STATUS_APPLIED;
            case NO_OP: return VideoContract.STATUS_NO_OP;
            case DUPLICATE: return VideoContract.STATUS_REJECTED;
            case STALE_REVISION: return VideoContract.STATUS_STALE;
            case STALE_TRIP: return VideoContract.STATUS_REJECTED;
            default: return VideoContract.STATUS_ERROR;
        }
    }
}
