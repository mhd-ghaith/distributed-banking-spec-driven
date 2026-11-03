package bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * T001 smoke test. Verifies constitution principles 1 and 2: the build runs tests from
 * tests/ on a Java 21 or newer runtime and the SQLite JDBC driver loads and answers a query.
 */
class SmokeTest {

    @Test
    void runtimeIsAtLeastJava21() {
        assertTrue(Runtime.version().feature() >= 21);
    }

    @Test
    void sqliteDriverLoadsAndAnswersAQuery() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}