package com.fnph.telepsychiatric.handler;

import com.fnph.telepsychiatric.common.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refusals reach the user with their reason; internal faults do not.
 */
class ConflictErrorResponseTest {

    @RestController
    static class Thrower {
        @GetMapping("/rule")
        String rule() {
            throw new BusinessRuleException("Not ready. Still outstanding: PRESCRIPTION.");
        }

        @GetMapping("/internal")
        String internal() {
            throw new IllegalStateException("Decryption failed. Has the encryption key changed?");
        }

        @GetMapping("/race")
        String race() {
            throw new ObjectOptimisticLockingFailureException("Wallet", 4L);
        }

        @GetMapping("/duplicate")
        String duplicate() {
            throw new DataIntegrityViolationException(
                    "Duplicate entry 'DB-CAP1' for key 'wallet_transactions.uk_wallet_tx_reference'");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("01 a business rule refusal is a 409 carrying its own message")
    void businessRule() throws Exception {
        mvc.perform(get("/rule"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("message").value("Not ready. Still outstanding: PRESCRIPTION."));
    }

    @Test
    @DisplayName("02 a plain IllegalStateException stays a 500 and its detail stays inside")
    void internalFault() throws Exception {
        mvc.perform(get("/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("message", not(containsString("encryption"))));
    }

    @Test
    @DisplayName("03 a lost concurrent write is a 409 telling the user to reload")
    void concurrentChange() throws Exception {
        mvc.perform(get("/race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("message", containsString("Reload")));
    }

    @Test
    @DisplayName("04 a constraint violation is a 409 and does not leak the table or key")
    void integrity() throws Exception {
        mvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("message", not(containsString("wallet_transactions"))));
    }
}
