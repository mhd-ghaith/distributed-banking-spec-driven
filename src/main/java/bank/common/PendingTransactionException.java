package bank.common;

/** A new debit was requested for an account that still has an unresolved transaction. */
public final class PendingTransactionException extends BankException {

    private final String accountId;
    private final long pendingKey;

    public PendingTransactionException(String accountId, long pendingKey) {
        super("account " + accountId + " has an unresolved transaction with key " + pendingKey);
        this.accountId = accountId;
        this.pendingKey = pendingKey;
    }

    public String accountId() {
        return accountId;
    }

    public long pendingKey() {
        return pendingKey;
    }
}