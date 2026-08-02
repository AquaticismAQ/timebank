package cn.timebank.common;

import java.security.SecureRandom;

/**
 * Server-generated unguessable IDs (contract §2.1: "frontend must not parse
 * IDs"). Format is a short module prefix plus a 26-char base32 random
 * string — a fixed prefix helps readability in logs and the database, but
 * nothing in the system may parse or derive meaning from it.
 */
public final class OpaqueIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private OpaqueIds() {
    }

    public static String generate(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 1 + 26);
        sb.append(prefix).append('_');
        for (int i = 0; i < 26; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
