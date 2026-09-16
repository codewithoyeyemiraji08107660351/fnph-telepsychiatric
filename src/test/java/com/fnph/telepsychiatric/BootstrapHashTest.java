package com.fnph.telepsychiatric;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BootstrapHashTest {
    @Test
    void printHash() {
        System.out.println(new BCryptPasswordEncoder().encode("ChangeMeOnFirstLogin"));
    }
}