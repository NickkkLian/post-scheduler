package postscheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

final class TestSupport {

    static final Instant T0 = Instant.parse("2026-01-01T09:00:00Z");

    private TestSupport() {}

    /** A clock the test moves by hand. Thread-safe, because the end-to-end test reads it from another thread. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A publisher that plays back queued outcomes (success when the queue is empty) and records every call.
     */
    static final class ScriptedPublisher implements Publisher {
        private final Deque<Object> outcomes = new ArrayDeque<>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        synchronized ScriptedPublisher thenFail(boolean retryable) {
            outcomes.add(new PublishException("network said no", retryable));
            return this;
        }

        synchronized ScriptedPublisher thenThrow(Throwable e) {
            outcomes.add(e);
            return this;
        }

        @Override
        public synchronized String publish(Post post) throws PublishException {
            calls.add(post.id());
            Object next = outcomes.poll();
            if (next instanceof PublishException e) {
                throw e;
            }
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof Error e) {
                throw e;
            }
            return "ext-" + calls.size();
        }
    }
}
