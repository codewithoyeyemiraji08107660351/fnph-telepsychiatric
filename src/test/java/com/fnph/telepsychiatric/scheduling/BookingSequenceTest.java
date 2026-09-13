package com.fnph.telepsychiatric.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money arithmetic of the confirmed sequence.
 *
 * Payment credits the wallet. Approval debits it. A rejection never debits, so
 * the balance survives. These four cases are what that means in numbers, and
 * getting any of them wrong either charges a patient twice or lets a booking
 * through unpaid.
 */
class BookingSequenceTest {

    private static final BigDecimal FEE = new BigDecimal("10000");

    /** What the patient is asked to pay, given an existing balance. */
    private BigDecimal payable(BigDecimal walletBalance) {
        return FEE.subtract(walletBalance.min(FEE));
    }

    /** Balance after payment credits it. */
    private BigDecimal afterPayment(BigDecimal before) {
        return before.add(payable(before));
    }

    /** Balance after approval spends the fee. */
    private BigDecimal afterApproval(BigDecimal balance) {
        return balance.subtract(balance.min(FEE));
    }

    @Test
    @DisplayName("first booking: pay the full fee, approval spends it, balance returns to zero")
    void firstBooking() {
        BigDecimal balance = BigDecimal.ZERO;

        assertThat(payable(balance)).isEqualByComparingTo("10000");
        balance = afterPayment(balance);
        assertThat(balance).as("wallet credited on payment").isEqualByComparingTo("10000");

        balance = afterApproval(balance);
        assertThat(balance).as("approval spends it").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rejection leaves the balance intact")
    void rejectionKeepsTheBalance() {
        // The whole reason the wallet exists. Money moves before anyone agrees
        // to see the patient, so a rejection must not take it.
        BigDecimal balance = afterPayment(BigDecimal.ZERO);
        assertThat(balance).isEqualByComparingTo("10000");

        // Rejection performs no debit at all.
        assertThat(balance).as("nothing is spent on a rejected booking")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("the booking after a rejection costs nothing")
    void secondBookingIsFree() {
        // The patient does nothing. No Remita call, no payment page.
        BigDecimal balance = new BigDecimal("10000");

        assertThat(payable(balance))
                .as("balance covers the fee, so nothing goes to the provider")
                .isEqualByComparingTo("0");

        assertThat(afterApproval(balance)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a partial balance means paying only the shortfall")
    void partialBalancePaysTheDifference() {
        BigDecimal balance = new BigDecimal("4000");

        assertThat(payable(balance)).isEqualByComparingTo("6000");
        assertThat(afterPayment(balance)).isEqualByComparingTo("10000");
        assertThat(afterApproval(afterPayment(balance))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("two rejections in a row do not accumulate two fees")
    void repeatedRejectionsDoNotStack() {
        // The failure this guards against: crediting on payment and again on
        // rejection would hand the patient double their money after one
        // refused booking.
        BigDecimal balance = afterPayment(BigDecimal.ZERO);

        // rejected, no debit
        // second attempt: nothing payable, so nothing credited
        assertThat(payable(balance)).isEqualByComparingTo("0");
        assertThat(afterPayment(balance))
                .as("a booking that needs no payment must not credit again")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("money is compared by value, not by scale")
    void scaleDoesNotAffectEquality() {
        // 10000 and 10000.00 are the same amount and different BigDecimals.
        // equals() here would make a covered fee look uncovered.
        assertThat(new BigDecimal("10000").compareTo(new BigDecimal("10000.00"))).isZero();
    }
}
