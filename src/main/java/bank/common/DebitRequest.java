package bank.common;

import java.util.regex.Pattern;

/**
 * A request to debit an account (spec FR-009).
 *
 * @param accountId the account identifier
 * @param amount    the amount in minor currency units
 * @param key       the sequence key of the transaction
 * @param retry     informational: false on the first attempt, true on later ones (FR-013)
 */
public record DebitRequest(String accountId, long amount, long key, boolean retry) {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Checks the rules every request must satisfy (FR-001, FR-017). This is the only place
     * where these rules are defined. The retry indicator does not affect validity.
     */
    public void requireValid() throws InvalidRequestException {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new InvalidRequestException(
                    "account identifier must be 1 to 64 letters, digits, '_' or '-'");
        }
        if (amount <= 0) {
            throw new InvalidRequestException("amount must be greater than zero");
        }
        if (key <= 0) {
            throw new InvalidRequestException("sequence key must be greater than zero");
        }
    }
}