package bank.common;

/** A new key asked for more than the account's balance. Carries the balance for the response. */
public final class InsufficientFundsException extends BankException {

    private final long balance;
    private final long requestedAmount;

    public InsufficientFundsException(long balance, long requestedAmount) {
        super("amount " + requestedAmount + " exceeds balance " + balance);
        this.balance = balance;
        this.requestedAmount = requestedAmount;
    }

    public long balance() {
        return balance;
    }

    public long requestedAmount() {
        return requestedAmount;
    }
}