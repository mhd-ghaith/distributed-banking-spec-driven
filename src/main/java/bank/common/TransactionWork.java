package bank.common;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The body of a database transaction. It runs between BEGIN IMMEDIATE and COMMIT.
 *
 * <p>Throwing any {@link BankException} (for example a rejection such as insufficient funds)
 * rolls the transaction back, and the same exception reaches the caller unchanged. Throwing
 * an {@link SQLException} rolls back and is reported as a {@link StorageException}.
 */
@FunctionalInterface
public interface TransactionWork<T> {

    T run(Connection connection) throws BankException, SQLException;
}