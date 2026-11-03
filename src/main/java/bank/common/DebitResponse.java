package bank.common;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * The server's reply to a debit request.
 *
 * <p>The account identifier and key echo the request so the client can match the reply to its
 * transaction (FR-023). When the request could not be decoded, the identifier is empty and the
 * key is 0. The balance is present whenever the account exists. The reason is empty when there
 * is nothing to explain.
 */
public record DebitResponse(Outcome outcome, String accountId, long key,
                            OptionalLong balance, String reason) {

    public DebitResponse {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(reason, "reason");
    }

    public static DebitResponse withBalance(Outcome outcome, String accountId, long key,
                                            long balance, String reason) {
        return new DebitResponse(outcome, accountId, key, OptionalLong.of(balance), reason);
    }

    public static DebitResponse withoutBalance(Outcome outcome, String accountId, long key,
                                               String reason) {
        return new DebitResponse(outcome, accountId, key, OptionalLong.empty(), reason);
    }
}