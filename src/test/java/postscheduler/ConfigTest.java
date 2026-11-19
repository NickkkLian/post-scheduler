package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsWhenNothingIsSet() {
        App.Config config = App.Config.fromEnv(Map.of());
        assertEquals(8080, config.port());
        assertEquals(Duration.ofSeconds(1), config.tick());
        assertEquals(50, config.batchSize());
        assertEquals(5, config.retryPolicy().maxAttempts());
    }

    @Test
    void zeroOrNegativeBatchSizeAndTickAreRejectedAtStartup() {
        assertEquals("BATCH_SIZE must be at least 1, got 0", error(Map.of("BATCH_SIZE", "0")));
        assertEquals("BATCH_SIZE must be at least 1, got -3", error(Map.of("BATCH_SIZE", "-3")));
        assertEquals("TICK_MS must be at least 1, got 0", error(Map.of("TICK_MS", "0")));
    }

    @Test
    void nonNumbersAndOutOfRangeRatesAreRejected() {
        assertEquals("PORT must be a whole number, got: eighty", error(Map.of("PORT", "eighty")));
        assertEquals("SIMULATED_FAILURE_RATE must be between 0 and 1, got 1.5",
                error(Map.of("SIMULATED_FAILURE_RATE", "1.5")));
    }

    @Test
    void portOutsideOneTo65535IsRejected() {
        assertEquals("PORT must be at most 65535, got 70000", error(Map.of("PORT", "70000")));
        assertEquals("PORT must be at least 1, got 0", error(Map.of("PORT", "0")));
    }

    @Test
    void retryBaseLargerThanRetryMaxIsRejectedNamingBoth() {
        assertEquals("RETRY_BASE_MS (100000) must not be larger than RETRY_MAX_MS (1000)",
                error(Map.of("RETRY_BASE_MS", "100000", "RETRY_MAX_MS", "1000")));
        assertEquals("RETRY_BASE_MS must be at least 1, got 0", error(Map.of("RETRY_BASE_MS", "0")));
        assertEquals("RETRY_MAX_MS must be at least 1, got -5", error(Map.of("RETRY_MAX_MS", "-5")));
    }

    private static String error(Map<String, String> env) {
        return assertThrows(IllegalArgumentException.class, () -> App.Config.fromEnv(env)).getMessage();
    }
}
