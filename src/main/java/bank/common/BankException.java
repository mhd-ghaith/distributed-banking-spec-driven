package bank.common;

/**
 * Parent of every error type in the system (constitution principle 6). Checked, so callers
 * must handle or declare each kind. Sealed, so the set of kinds is closed. All subclasses are
 * in this package because the project has no module descriptor.
 */
public abstract sealed class BankException extends Exception
        permits InvalidRequestException, InsufficientFundsException, StaleKeyException,
                StorageException, NetworkFailureException, AttemptTimeoutException,
                PendingTransactionException {

    protected BankException(String message) {
        super(message);
    }

    protected BankException(String message, Throwable cause) {
        super(message, cause);
    }
}