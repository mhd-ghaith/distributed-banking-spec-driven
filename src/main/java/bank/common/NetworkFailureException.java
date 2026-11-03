package bank.common;

/** A connection failed or was closed before a response arrived. Not a definitive outcome. */
public final class NetworkFailureException extends BankException {

    public NetworkFailureException(String message) {
        super(message);
    }

    public NetworkFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}