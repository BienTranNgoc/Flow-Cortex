package com.flowcortex.user.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt password hashing (design.md D4/R-021) — one bean shared by {@link
 * UserServiceImpl} (hashing on create/update) and {@link AuthServiceImpl}
 * (verifying on login).
 */
@Configuration
class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
