package com.fnph.telepsychiatric.session;

/**
 * A request refused because too many were made recently, from one address or
 * against one account. Answered with 429 and the message as written, which
 * says how long to wait or where to go instead.
 */
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
