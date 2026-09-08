package com.fnph.telepsychiatric.common;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID generator for externally visible record identifiers.
 *
 * Why a second identifier exists at all:
 *
 * The primary key is a BIGINT auto-increment because InnoDB clusters the table
 * on it, and a sequential key keeps inserts appending to the end of the index
 * rather than scattering across it. A random UUID primary key on MySQL costs
 * roughly an order of magnitude in write throughput on a large table and
 * inflates every foreign key and secondary index that references it.
 *
 * But a sequential integer must never appear in a URL here. Anyone holding
 * /api/v1/appointments/4471 knows the system has issued about 4,471
 * appointments, and can walk the range probing for authorisation gaps. The
 * acceptance criteria require that an altered record identifier returns a
 * denial; they do not require that the identifier be unguessable, but making it
 * unguessable removes a whole class of enumeration attack rather than relying on
 * every future endpoint remembering to check ownership.
 *
 * So: BIGINT internally for joins and indexes, ULID externally for URLs and
 * documents. ULID rather than UUIDv4 because it is lexicographically sortable by
 * creation time, which makes it usable in an ordered index and in a document
 * reference that a human might read out over the phone.
 *
 * Format is the standard 26-character Crockford base32 encoding: 48 bits of
 * millisecond timestamp followed by 80 bits from a cryptographic source.
 * Crockford omits I, L, O and U, so it cannot produce an accidental word and
 * cannot be misread between 1 and I or 0 and O.
 */
public final class PublicId {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int LENGTH = 26;

    private PublicId() {
    }

    public static String generate() {
        return generate(Instant.now().toEpochMilli());
    }

    static String generate(long epochMilli) {
        byte[] value = new byte[16];

        // 48-bit timestamp, most significant byte first.
        value[0] = (byte) (epochMilli >>> 40);
        value[1] = (byte) (epochMilli >>> 32);
        value[2] = (byte) (epochMilli >>> 24);
        value[3] = (byte) (epochMilli >>> 16);
        value[4] = (byte) (epochMilli >>> 8);
        value[5] = (byte) epochMilli;

        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        System.arraycopy(entropy, 0, value, 6, 10);

        return encode(value);
    }

    /** Crockford base32 over 128 bits, producing 26 characters. */
    private static String encode(byte[] value) {
        char[] out = new char[LENGTH];
        int bitBuffer = 0;
        int bitCount = 0;
        int index = LENGTH - 1;

        for (int i = value.length - 1; i >= 0; i--) {
            bitBuffer |= (value[i] & 0xFF) << bitCount;
            bitCount += 8;
            while (bitCount >= 5) {
                out[index--] = ALPHABET[bitBuffer & 0x1F];
                bitBuffer >>>= 5;
                bitCount -= 5;
            }
        }
        if (bitCount > 0 && index >= 0) {
            out[index--] = ALPHABET[bitBuffer & 0x1F];
        }
        while (index >= 0) {
            out[index--] = ALPHABET[0];
        }
        return new String(out);
    }

    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (indexOf(candidate.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(char c) {
        char upper = Character.toUpperCase(c);
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == upper) {
                return i;
            }
        }
        return -1;
    }
}
