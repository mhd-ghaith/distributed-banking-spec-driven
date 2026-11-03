package bank.common;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts requests and responses to and from text (plan decision D-4, spec FR-009).
 *
 * <p>Each message is a sequence of lines of the form {@code name=value}. A request has the
 * fields account, amount, key and retry. A response has outcome, account and key, and
 * optionally balance and reason. Everything after the first {@code =} on a line is the value.
 *
 * <p>Decoding is strict: unknown, repeated or missing fields, lines without {@code =}, and
 * numbers that are not plain decimal whole numbers of at most 18 digits are rejected. The
 * order of fields, blank lines and {@code \r\n} line endings are tolerated. Limiting numbers
 * to 18 digits means they always fit in a {@code long}.
 *
 * <p>This class has no state, so it is safe to use from many threads.
 */
public final class WireFormat {

    /** Content type for request and response bodies. */
    public static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    private static final String ACCOUNT = "account";
    private static final String AMOUNT = "amount";
    private static final String KEY = "key";
    private static final String RETRY = "retry";
    private static final String OUTCOME = "outcome";
    private static final String BALANCE = "balance";
    private static final String REASON = "reason";

    private static final Set<String> REQUEST_FIELDS = Set.of(ACCOUNT, AMOUNT, KEY, RETRY);
    private static final Set<String> RESPONSE_FIELDS =
            Set.of(OUTCOME, ACCOUNT, KEY, BALANCE, REASON);

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,18}");

    private WireFormat() {
    }

    /** Encodes a request. The request is not validated here. */
    public static String encodeRequest(DebitRequest request) {
        return ACCOUNT + "=" + request.accountId() + "\n"
                + AMOUNT + "=" + request.amount() + "\n"
                + KEY + "=" + request.key() + "\n"
                + RETRY + "=" + request.retry() + "\n";
    }

    /**
     * Decodes and validates a request (FR-017).
     *
     * @throws InvalidRequestException if the text is malformed or the request is not valid
     */
    public static DebitRequest decodeRequest(String body) throws InvalidRequestException {
        Map<String, String> fields = parseFields(body, REQUEST_FIELDS);
        String accountId = required(fields, ACCOUNT);
        long amount = number(AMOUNT, required(fields, AMOUNT));
        long key = number(KEY, required(fields, KEY));
        boolean retry = bool(RETRY, required(fields, RETRY));

        DebitRequest request = new DebitRequest(accountId, amount, key, retry);
        request.requireValid();
        return request;
    }

    /** Encodes a response. Line breaks in the account or reason text become spaces. */
    public static String encodeResponse(DebitResponse response) {
        StringBuilder text = new StringBuilder();
        text.append(OUTCOME).append('=').append(response.outcome().name()).append('\n');
        text.append(ACCOUNT).append('=').append(singleLine(response.accountId())).append('\n');
        text.append(KEY).append('=').append(response.key()).append('\n');
        if (response.balance().isPresent()) {
            text.append(BALANCE).append('=').append(response.balance().getAsLong()).append('\n');
        }
        if (!response.reason().isEmpty()) {
            text.append(REASON).append('=').append(singleLine(response.reason())).append('\n');
        }
        return text.toString();
    }

    /**
     * Decodes a response. This method never throws: a response that cannot be read becomes an
     * ERROR outcome with an empty account identifier and key 0. ERROR is not definitive, so the
     * client simply retries the same key (FR-022).
     */
    public static DebitResponse decodeResponse(String body) {
        try {
            Map<String, String> fields = parseFields(body, RESPONSE_FIELDS);
            Outcome outcome = outcome(required(fields, OUTCOME));
            String accountId = required(fields, ACCOUNT);
            long key = number(KEY, required(fields, KEY));
            if (key < 0) {
                throw new InvalidRequestException("key must not be negative");
            }
            OptionalLong balance = OptionalLong.empty();
            String balanceText = fields.get(BALANCE);
            if (balanceText != null) {
                long value = number(BALANCE, balanceText);
                if (value < 0) {
                    throw new InvalidRequestException("balance must not be negative");
                }
                balance = OptionalLong.of(value);
            }
            String reason = fields.getOrDefault(REASON, "");
            return new DebitResponse(outcome, accountId, key, balance, reason);
        } catch (InvalidRequestException e) {
            return DebitResponse.withoutBalance(
                    Outcome.ERROR, "", 0, "unreadable response: " + e.getMessage());
        }
    }

    private static Map<String, String> parseFields(String body, Set<String> allowed)
            throws InvalidRequestException {
        if (body == null) {
            throw new InvalidRequestException("empty message");
        }
        Map<String, String> fields = new HashMap<>();
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1)
                    : rawLine;
            if (line.isEmpty()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new InvalidRequestException(
                        "line without a field name and '=': " + abbreviate(line));
            }
            String name = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if (!allowed.contains(name)) {
                throw new InvalidRequestException("unknown field: " + abbreviate(name));
            }
            if (fields.putIfAbsent(name, value) != null) {
                throw new InvalidRequestException("field repeated: " + name);
            }
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String name)
            throws InvalidRequestException {
        String value = fields.get(name);
        if (value == null) {
            throw new InvalidRequestException("missing field: " + name);
        }
        return value;
    }

    private static long number(String name, String value) throws InvalidRequestException {
        if (!INTEGER.matcher(value).matches()) {
            throw new InvalidRequestException(
                    name + " must be a whole number of at most 18 digits");
        }
        return Long.parseLong(value);
    }

    private static boolean bool(String name, String value) throws InvalidRequestException {
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new InvalidRequestException(name + " must be true or false");
    }

    private static Outcome outcome(String value) throws InvalidRequestException {
        try {
            return Outcome.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("unknown outcome: " + abbreviate(value));
        }
    }

    private static String singleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}