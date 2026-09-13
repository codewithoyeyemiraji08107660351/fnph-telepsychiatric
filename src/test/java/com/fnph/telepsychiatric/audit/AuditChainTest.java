package com.fnph.telepsychiatric.audit;

import com.fnph.telepsychiatric.security.crypto.Tokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hash chain, tested as an algorithm rather than through the database.
 *
 * The property that matters is simple enough to state and to break: altering or
 * removing any entry must invalidate every entry after it. If that holds, the
 * trail is tamper-evident; if it does not, the whole claim collapses.
 */
class AuditChainTest {

    /** Mirrors AuditService.linkToChain. */
    private String chainHash(String previous, String username, String action, String performedAt) {
        return Tokens.hash(String.join("|", previous, username, action, "", "0",
                "SUCCESS", "", "", performedAt));
    }

    private List<String[]> buildChain(int length) {
        List<String[]> chain = new ArrayList<>();
        String previous = "GENESIS";
        for (int i = 0; i < length; i++) {
            String username = "user" + i;
            String action = "RECORD_VIEWED";
            String at = "2026-09-07T09:0" + (i % 10) + ":00";
            String hash = chainHash(previous, username, action, at);
            chain.add(new String[]{previous, username, action, at, hash});
            previous = hash;
        }
        return chain;
    }

    private boolean verify(List<String[]> chain) {
        String expectedPrevious = "GENESIS";
        for (String[] row : chain) {
            if (!expectedPrevious.equals(row[0])) {
                return false;
            }
            if (!chainHash(row[0], row[1], row[2], row[3]).equals(row[4])) {
                return false;
            }
            expectedPrevious = row[4];
        }
        return true;
    }

    @Test
    @DisplayName("an untouched chain verifies")
    void intactChainVerifies() {
        assertThat(verify(buildChain(50))).isTrue();
    }

    @Test
    @DisplayName("editing an entry breaks the chain")
    void editingAnEntryIsDetected() {
        // The obvious attack: change what an entry says happened.
        List<String[]> chain = buildChain(50);
        chain.get(20)[2] = "RECORD_UPDATED";

        assertThat(verify(chain)).isFalse();
    }

    @Test
    @DisplayName("removing an entry breaks the chain")
    void removingAnEntryIsDetected() {
        // The subtler attack, and the more likely one: delete the row showing
        // you were there.
        List<String[]> chain = buildChain(50);
        chain.remove(30);

        assertThat(verify(chain)).isFalse();
    }

    @Test
    @DisplayName("inserting an entry breaks the chain")
    void insertingAnEntryIsDetected() {
        List<String[]> chain = buildChain(50);
        chain.add(10, new String[]{"FORGED", "attacker", "RECORD_VIEWED",
                "2026-09-07T09:00:00", "FORGEDHASH"});

        assertThat(verify(chain)).isFalse();
    }

    @Test
    @DisplayName("re-hashing an edited entry still breaks every entry after it")
    void rehashingDoesNotHelp() {
        // Someone who knows the algorithm might edit a row and recompute its
        // own hash. That fixes one row and breaks the link into the next,
        // which is exactly the property that makes the chain worth having:
        // covering the tracks means rewriting every subsequent row.
        List<String[]> chain = buildChain(50);
        String[] tampered = chain.get(20);
        tampered[2] = "RECORD_UPDATED";
        tampered[4] = chainHash(tampered[0], tampered[1], tampered[2], tampered[3]);

        assertThat(verify(chain)).isFalse();
    }

    @Test
    @DisplayName("the first entry links to a known starting point")
    void chainStartsAtGenesis() {
        // Without a fixed start, an attacker could truncate the front of the
        // chain and the remainder would still verify.
        assertThat(buildChain(5).get(0)[0]).isEqualTo("GENESIS");
    }
}
