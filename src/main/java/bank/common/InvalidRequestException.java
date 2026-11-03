package bank.common;

/** The request is malformed, names an unknown account, or has a non-positive amount or key. */
public final class InvalidRequestException extends BankException {

    public InvalidRequestException(String message) {
        super(message);
    }
}