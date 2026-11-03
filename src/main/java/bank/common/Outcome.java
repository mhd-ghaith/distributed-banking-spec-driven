package bank.common;

/**
 * Result of a debit request, as carried in a response (spec FR-022).
 * Every outcome except ERROR is definitive: the client may stop retrying and report it.
 */
public enum Outcome {
    SUCCESS(true),
    DUPLICATE_ACKNOWLEDGED(true),
    INSUFFICIENT_FUNDS(true),
    INVALID_REQUEST(true),
    STALE_KEY(true),
    ERROR(false);

    private final boolean definitive;

    Outcome(boolean definitive) {
        this.definitive = definitive;
    }

    /** True when the server has made a final decision about the request. False only for ERROR. */
    public boolean isDefinitive() {
        return definitive;
    }
}