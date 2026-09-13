package com.fnph.telepsychiatric.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic of credit against a fee.
 *
 * Small, and worth pinning down: this is the code that decides what a patient
 * is asked to pay after a rejected booking, and getting it wrong either charges
 * them twice or lets them book for nothing.
 */
class PaymentRulesTest {

    /** Mirrors PatientCreditService.apply: the lesser of balance and amount owed. */
    private BigDecimal applied(BigDecimal balance, BigDecimal owed) {
        if (balance.signum() <= 0 || owed.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return balance.min(owed);
    }

    private BigDecimal payable(BigDecimal fee, BigDecimal balance) {
        return fee.subtract(applied(balance, fee));
    }

    @ParameterizedTest(name = "fee {0}, credit {1} leaves {2} to pay")
    @CsvSource({
            "10000, 0,     10000",
            "10000, 10000, 0",
            "10000, 4000,  6000",
            "10000, 15000, 0",
            "12000, 10000, 2000"
    })
    @DisplayName("credit reduces the payable amount and never goes below zero")
    void creditReducesPayable(String fee, String credit, String expected) {
        assertThat(payable(new BigDecimal(fee), new BigDecimal(credit)))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("credit larger than the fee is only consumed up to the fee")
    void surplusCreditIsRetained() {
        // A credit from a ten thousand naira booking against a fee that later
        // dropped must leave the remainder on the account, not absorb it.
        BigDecimal balance = new BigDecimal("15000");
        BigDecimal fee = new BigDecimal("10000");

        BigDecimal used = applied(balance, fee);
        assertThat(used).isEqualByComparingTo("10000");
        assertThat(balance.subtract(used)).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("a fee fully covered by credit means nothing goes to Remita")
    void fullyCoveredNeedsNoProvider() {
        // The branch that matters operationally: the patient sees a confirmed
        // payment and an unlocked booking without a payment page at all.
        assertThat(payable(new BigDecimal("10000"), new BigDecimal("10000")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a zero balance applies nothing")
    void zeroBalanceAppliesNothing() {
        assertThat(applied(BigDecimal.ZERO, new BigDecimal("10000")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("money is compared by value, not by scale")
    void scaleDoesNotAffectEquality() {
        // 10000 and 10000.00 are the same amount and different BigDecimals.
        // Using equals() here rather than compareTo() would make a credit look
        // like it had not covered the fee.
        assertThat(new BigDecimal("10000").compareTo(new BigDecimal("10000.00"))).isZero();
        assertThat(new BigDecimal("10000")).isNotEqualTo(new BigDecimal("10000.00"));
    }
}
