package bank.common;

/** No response arrived within the per-attempt timeout. Not a definitive outcome. */
public final class AttemptTimeoutException extends BankException {

    public AttemptTimeoutException(String message) {
        super(message);
    }

    public AttemptTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}