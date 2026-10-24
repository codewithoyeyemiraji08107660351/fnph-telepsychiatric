package com.fnph.telepsychiatric.common;

/**
 * A request refused because of where the record stands, with a message
 * written for the person who made it.
 *
 * "Not ready. Still outstanding: PRESCRIPTION", "That referral has already
 * been submitted", "That appointment has already passed". These were thrown as
 * plain IllegalStateException, which the error handler cannot tell apart from
 * a genuine fault, so it answered all of them with "An unexpected error
 * occurred" and the coordinator never learned what to chase.
 *
 * Returned as 409 with the message. Plain IllegalStateException stays a 500
 * with a generic message on purpose: those carry internal detail (encryption
 * key problems, renderer failures, configuration) that must not reach a user.
 *
 * Extends IllegalStateException so existing callers and tests that expect one
 * still see one.
 */
public class BusinessRuleException extends IllegalStateException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
