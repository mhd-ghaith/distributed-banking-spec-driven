package bank.common;

/** The key is lower than the account's last executed key. Carries the balance for the response. */
public final class StaleKeyException extends BankException {

    private final long balance;
    private final long lastExecutedKey;

    public StaleKeyException(long balance, long lastExecutedKey) {
        super("key is lower than the last executed key " + lastExecutedKey);
        this.balance = balance;
        this.lastExecutedKey = lastExecutedKey;
    }

    public long balance() {
        return balance;
    }

    public long lastExecutedKey() {
        return lastExecutedKey;
    }
}