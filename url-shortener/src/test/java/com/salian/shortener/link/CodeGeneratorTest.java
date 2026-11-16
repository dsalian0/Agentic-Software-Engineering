package com.salian.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    @Test
    void generatesCodesOfConfiguredLengthFromBase62Alphabet() {
        CodeGenerator generator = new CodeGenerator(new Random(42), 7);

        for (int i = 0; i < 1_000; i++) {
            String code = generator.next();
            assertThat(code).hasSize(7);
            assertThat(code.chars()).allMatch(c -> CodeGenerator.ALPHABET.indexOf(c) >= 0);
        }
    }

    @Test
    void codesAreNotSequential() {
        CodeGenerator generator = new CodeGenerator(new Random(7), 7);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.next());
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThatThrownBy(() -> new CodeGenerator(new Random(), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
