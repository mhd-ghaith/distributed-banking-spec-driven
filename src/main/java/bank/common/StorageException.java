package bank.common;

/** A database operation failed, or the database stayed locked past the busy timeout. */
public final class StorageException extends BankException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}