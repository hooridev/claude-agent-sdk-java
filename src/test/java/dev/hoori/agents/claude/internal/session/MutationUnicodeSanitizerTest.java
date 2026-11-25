package dev.hoori.agents.claude.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MutationUnicodeSanitizerTest {

    @Test
    void passesCleanStringsThrough() {
        assertEquals("hello world", UnicodeSanitizer.sanitize("hello world"));
        assertEquals("über ünïcödé 日本", UnicodeSanitizer.sanitize("über ünïcödé 日本"));
    }

    @Test
    void stripsZeroWidthBomDirectionalAndPrivateUse() {
        assertEquals("abc", UnicodeSanitizer.sanitize("a​b‍c"));
        assertEquals("abc", UnicodeSanitizer.sanitize("﻿abc"));
        assertEquals("abc", UnicodeSanitizer.sanitize("a‮b⁦c‏"));
        assertEquals("abc", UnicodeSanitizer.sanitize("abc"));
    }

    @Test
    void appliesNfkc() {
        assertEquals("fi", UnicodeSanitizer.sanitize("ﬁ"));
        assertEquals("ABC", UnicodeSanitizer.sanitize("ＡＢＣ")); // fullwidth
    }

    @Test
    void iteratesUntilStable() {
        String once = UnicodeSanitizer.sanitize("x​ﬁ‪y");
        assertEquals("xfiy", once);
        assertEquals(once, UnicodeSanitizer.sanitize(once));
    }
}
