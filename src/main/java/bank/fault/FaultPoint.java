package bank.fault;

/** Where in the request path a fault can be injected (plan section 9.1). */
public enum FaultPoint {
    /** In the client's transport, before the request is sent. */
    CLIENT_BEFORE_SEND,
    /** In the server's handler, after decoding the request and before the database transaction. */
    SERVER_BEFORE_EXECUTION,
    /** In the server's handler, after the transaction has ended and before the response is written. */
    SERVER_AFTER_COMMIT
}