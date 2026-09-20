package llamabendb.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantSortKeyTest {

    @Test
    void planExampleOrdering() {
        assertTrue(QuantSortKey.of("IQ3_XS").compareTo(QuantSortKey.of("Q4_0")) < 0);
        assertTrue(QuantSortKey.of("Q4_0").compareTo(QuantSortKey.of("Q5_K_M")) < 0);
    }

    @Test
    void bitsArePrimaryThenSizeClass() {
        assertTrue(QuantSortKey.of("Q4_0").compareTo(QuantSortKey.of("Q4_K_M")) < 0);
        assertTrue(QuantSortKey.of("Q5_K_M").compareTo(QuantSortKey.of("Q6_K")) < 0);
        assertTrue(QuantSortKey.of("IQ3_XXS").compareTo(QuantSortKey.of("IQ3_XS")) < 0);
    }

    @Test
    void unparseableQuantsFallToEnd() {
        String fallback = QuantSortKey.of("UD-IQ3_XXS");
        assertTrue(fallback.startsWith("99|"));
        assertTrue(QuantSortKey.of("Q8_0").compareTo(fallback) < 0);
    }

    @Test
    void ternaryQuantsParse() {
        assertTrue(QuantSortKey.of("TQ1_0").startsWith("01|"));
        assertTrue(QuantSortKey.of("PTQ1_0").startsWith("01|"));
    }
}
