package bank.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WireFormatTest {

    private static String body(String account, String amount, String key, String retry) {
        return "account=" + account + "\namount=" + amount + "\nkey=" + key
                + "\nretry=" + retry + "\n";
    }

    // ---------------------------------------------------------------- requests

    @Test
    @DisplayName("FR-009: an encoded request has the four fields in a fixed order")
    void requestHasTheDocumentedFormat() {
        assertEquals("account=ACC-1\namount=300\nkey=1\nretry=false\n",
                WireFormat.encodeRequest(new DebitRequest("ACC-1", 300, 1, false)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("FR-009: a request survives encoding and decoding")
    void requestRoundTrip(boolean retry) throws Exception {
        DebitRequest original = new DebitRequest("acc_7-X", 123456789, 42, retry);
        assertEquals(original, WireFormat.decodeRequest(WireFormat.encodeRequest(original)));
    }

    @Test
    @DisplayName("FR-009: field order, blank lines and CRLF line endings are tolerated")
    void decodingToleratesOrderBlankLinesAndCrLf() throws Exception {
        String text = "\r\nretry=true\r\nkey=9\r\n\r\namount=50\r\naccount=ACC-2\r\n";
        assertEquals(new DebitRequest("ACC-2", 50, 9, true), WireFormat.decodeRequest(text));
    }

    @Test
    @DisplayName("FR-001: numbers of 18 digits are accepted")
    void eighteenDigitNumbersAreAccepted() throws Exception {
        String text = body("ACC-1", "999999999999999999", "999999999999999999", "false");
        assertEquals(new DebitRequest("ACC-1", 999_999_999_999_999_999L,
                999_999_999_999_999_999L, false), WireFormat.decodeRequest(text));
    }

    static Stream<String> badRequestBodies() {
        return Stream.of(
                "",
                "\n\n",
                "account=ACC-1\namount=5\nkey=1\n",
                "amount=5\nkey=1\nretry=false\n",
                "account=ACC-1\nkey=1\nretry=false\n",
                "account=ACC-1\namount=5\nretry=false\n",
                body("ACC-1", "5", "1", "false") + "account=ACC-2\n",
                body("ACC-1", "5", "1", "false") + "extra=1\n",
                body("ACC-1", "5", "1", "false") + "garbage\n",
                body("ACC-1", "5", "1", "false") + "=5\n",
                body("ACC-1", "abc", "1", "false"),
                body("ACC-1", "+5", "1", "false"),
                body("ACC-1", " 5", "1", "false"),
                body("ACC-1", "5 ", "1", "false"),
                body("ACC-1", "1.5", "1", "false"),
                body("ACC-1", "", "1", "false"),
                body("ACC-1", "1" + "0".repeat(18), "1", "false"),
                body("ACC-1", "0", "1", "false"),
                body("ACC-1", "-3", "1", "false"),
                body("ACC-1", "5", "abc", "false"),
                body("ACC-1", "5", "0", "false"),
                body("ACC-1", "5", "-1", "false"),
                body("a b", "5", "1", "false"),
                body("", "5", "1", "false"),
                body("ACC-1", "5", "1", "yes"),
                body("ACC-1", "5", "1", "TRUE"),
                body("ACC-1", "5", "1", ""));
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("badRequestBodies")
    @DisplayName("FR-017: a malformed or invalid request is rejected")
    void badRequestsAreRejected(String text) {
        assertThrows(InvalidRequestException.class, () -> WireFormat.decodeRequest(text));
    }

    @Test
    @DisplayName("FR-017: a null request body is rejected")
    void nullRequestBodyIsRejected() {
        assertThrows(InvalidRequestException.class, () -> WireFormat.decodeRequest(null));
    }

    @Test
    @DisplayName("FR-017: the rejection names the missing field")
    void rejectionNamesTheMissingField() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> WireFormat.decodeRequest("account=ACC-1\namount=5\nkey=1\n"));
        assertTrue(e.getMessage().contains("retry"));
    }

    // --------------------------------------------------------------- responses

    @Test
    @DisplayName("FR-022: an encoded response has the documented format")
    void responseHasTheDocumentedFormat() {
        DebitResponse response = DebitResponse.withBalance(Outcome.SUCCESS, "ACC-1", 1, 700, "");
        assertEquals("outcome=SUCCESS\naccount=ACC-1\nkey=1\nbalance=700\n",
                WireFormat.encodeResponse(response));
    }

    @Test
    @DisplayName("FR-022: a response with balance and no reason survives a round trip")
    void responseWithBalanceRoundTrip() {
        DebitResponse original =
                DebitResponse.withBalance(Outcome.SUCCESS, "ACC-1", 3, 700, "");
        assertEquals(original, WireFormat.decodeResponse(WireFormat.encodeResponse(original)));
    }

    @Test
    @DisplayName("FR-022: a response with balance and reason survives a round trip")
    void responseWithBalanceAndReasonRoundTrip() {
        DebitResponse original = DebitResponse.withBalance(
                Outcome.INSUFFICIENT_FUNDS, "ACC-1", 2, 100, "amount 500 exceeds balance 100");
        assertEquals(original, WireFormat.decodeResponse(WireFormat.encodeResponse(original)));
    }

    @Test
    @DisplayName("FR-022: a response without balance survives a round trip")
    void responseWithoutBalanceRoundTrip() {
        DebitResponse original = DebitResponse.withoutBalance(
                Outcome.INVALID_REQUEST, "NOPE", 1, "unknown account");
        DebitResponse decoded = WireFormat.decodeResponse(WireFormat.encodeResponse(original));
        assertEquals(original, decoded);
        assertEquals(OptionalLong.empty(), decoded.balance());
    }

    @Test
    @DisplayName("FR-017: the response to an undecodable request (empty account, key 0) "
            + "survives a round trip")
    void responseWithEmptyEchoRoundTrip() {
        DebitResponse original =
                DebitResponse.withoutBalance(Outcome.INVALID_REQUEST, "", 0, "malformed");
        assertEquals(original, WireFormat.decodeResponse(WireFormat.encodeResponse(original)));
    }

    @Test
    @DisplayName("FR-022: every outcome survives a round trip, including a balance of zero")
    void everyOutcomeRoundTrips() {
        for (Outcome outcome : Outcome.values()) {
            DebitResponse original = DebitResponse.withBalance(outcome, "ACC-9", 12, 0, "");
            DebitResponse decoded = WireFormat.decodeResponse(WireFormat.encodeResponse(original));
            assertEquals(original, decoded, outcome.name());
        }
    }

    @Test
    @DisplayName("FR-022: line breaks in the reason cannot break the format")
    void lineBreaksInTheReasonAreReplaced() {
        DebitResponse original = DebitResponse.withBalance(
                Outcome.INSUFFICIENT_FUNDS, "ACC-1", 2, 100, "line1\nline2\r\nline3");
        String text = WireFormat.encodeResponse(original);
        assertEquals(5, text.split("\n").length);

        DebitResponse decoded = WireFormat.decodeResponse(text);
        assertEquals(Outcome.INSUFFICIENT_FUNDS, decoded.outcome());
        assertEquals("line1 line2  line3", decoded.reason());
    }

    @Test
    @DisplayName("FR-022: an equals sign inside the reason is kept")
    void equalsSignInsideTheReasonIsKept() {
        DebitResponse original = DebitResponse.withBalance(
                Outcome.ERROR, "ACC-1", 2, 100, "a=b=c");
        assertEquals("a=b=c",
                WireFormat.decodeResponse(WireFormat.encodeResponse(original)).reason());
    }

    @Test
    @DisplayName("FR-022: CRLF line endings are tolerated in responses")
    void responseToleratesCrLf() {
        DebitResponse decoded = WireFormat.decodeResponse(
                "outcome=DUPLICATE_ACKNOWLEDGED\r\naccount=ACC-1\r\nkey=1\r\nbalance=700\r\n");
        assertEquals(DebitResponse.withBalance(
                Outcome.DUPLICATE_ACKNOWLEDGED, "ACC-1", 1, 700, ""), decoded);
    }

    @Test
    @DisplayName("FR-022: an ERROR outcome sent by the server is decoded as sent")
    void serverErrorOutcomeIsDecodedAsSent() {
        DebitResponse decoded = WireFormat.decodeResponse(
                "outcome=ERROR\naccount=ACC-1\nkey=4\nreason=database busy\n");
        assertEquals(Outcome.ERROR, decoded.outcome());
        assertEquals("ACC-1", decoded.accountId());
        assertEquals(4, decoded.key());
        assertEquals("database busy", decoded.reason());
    }

    static Stream<String> unreadableResponseBodies() {
        return Stream.of(
                "",
                "\n",
                "garbage",
                "account=ACC-1\nkey=1\n",
                "outcome=SUCCESS\nkey=1\n",
                "outcome=SUCCESS\naccount=ACC-1\n",
                "outcome=MAYBE\naccount=ACC-1\nkey=1\n",
                "outcome=success\naccount=ACC-1\nkey=1\n",
                "outcome=SUCCESS\naccount=ACC-1\nkey=abc\n",
                "outcome=SUCCESS\naccount=ACC-1\nkey=-1\n",
                "outcome=SUCCESS\naccount=ACC-1\nkey=1\nbalance=-5\n",
                "outcome=SUCCESS\naccount=ACC-1\nkey=1\nbalance=abc\n",
                "outcome=SUCCESS\noutcome=ERROR\naccount=ACC-1\nkey=1\n",
                "outcome=SUCCESS\naccount=ACC-1\nkey=1\nextra=1\n");
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("unreadableResponseBodies")
    @DisplayName("FR-022: an unreadable response becomes a non-definitive ERROR")
    void unreadableResponsesBecomeError(String text) {
        DebitResponse decoded = WireFormat.decodeResponse(text);
        assertEquals(Outcome.ERROR, decoded.outcome());
        assertFalse(decoded.outcome().isDefinitive());
        assertEquals("", decoded.accountId());
        assertEquals(0, decoded.key());
        assertFalse(decoded.balance().isPresent());
        assertTrue(decoded.reason().startsWith("unreadable response"));
    }

    @Test
    @DisplayName("FR-022: a null response body becomes a non-definitive ERROR")
    void nullResponseBodyBecomesError() {
        DebitResponse decoded = WireFormat.decodeResponse(null);
        assertEquals(Outcome.ERROR, decoded.outcome());
        assertFalse(decoded.outcome().isDefinitive());
    }
}