package com.flowcortex.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaxUtf8BytesValidatorTest {

    // U+1EBF is 1 character and 3 bytes in UTF-8.
    private static final String THREE_BYTE_CHAR = "ế";

    private static ValidatorFactory factory;
    private static Validator validator;

    record Bounded(@MaxUtf8Bytes(72) String value) {
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<ConstraintViolation<Bounded>> validate(String value) {
        return validator.validate(new Bounded(value));
    }

    @Test
    void exactly72BytesIsValid() {
        assertThat(validate(THREE_BYTE_CHAR.repeat(24))).isEmpty();
        assertThat(validate("a".repeat(72))).isEmpty();
    }

    @Test
    void seventyThreeBytesIsRejectedEvenAtOnly25Characters() {
        String password = THREE_BYTE_CHAR.repeat(24) + "a";
        assertThat(password).hasSize(25);

        Set<ConstraintViolation<Bounded>> violations = validate(password);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("value");
            assertThat(violation.getMessage()).isEqualTo("must be at most 72 bytes in UTF-8");
        });
    }

    @Test
    void seventyThreeAsciiCharactersAreRejected() {
        assertThat(validate("a".repeat(73))).hasSize(1);
    }

    @Test
    void nullIsLeftToNotBlank() {
        assertThat(validate(null)).isEmpty();
    }
}
