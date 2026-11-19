package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(10));

    @Test
    void delayDoublesUntilTheCap() {
        assertEquals(Duration.ofSeconds(1), policy.delayAfter(1));
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(2));
        assertEquals(Duration.ofSeconds(4), policy.delayAfter(3));
        assertEquals(Duration.ofSeconds(8), policy.delayAfter(4));
        assertEquals(Duration.ofSeconds(10), policy.delayAfter(5));
    }

    @Test
    void veryLargeAttemptCountsDoNotOverflow() {
        assertEquals(Duration.ofSeconds(10), policy.delayAfter(64));
        assertEquals(Duration.ofSeconds(10), policy.delayAfter(Integer.MAX_VALUE));
    }

    @Test
    void exhaustedOnceTheLastAllowedAttemptFails() {
        assertFalse(policy.exhausted(4));
        assertTrue(policy.exhausted(5));
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> policy.delayAfter(0));
    }
}
