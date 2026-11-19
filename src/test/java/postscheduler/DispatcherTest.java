package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static postscheduler.TestSupport.T0;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import postscheduler.TestSupport.MutableClock;
import postscheduler.TestSupport.ScriptedPublisher;

class DispatcherTest {

    private final MutableClock clock = new MutableClock(T0);
    private final PostStore store = new PostStore();
    private final ScriptedPublisher publisher = new ScriptedPublisher();
    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(60));
    private final Dispatcher dispatcher = new Dispatcher(store, publisher, policy, clock, 50);

    private Post schedule(String text, Duration fromNow) {
        return store.create(new NewPost("x", text, clock.instant().plus(fromNow)), null, clock.instant()).post();
    }

    private Post reload(Post post) {
        return store.get(post.id()).orElseThrow();
    }

    @Test
    void doesNothingBeforeThePublishTime() {
        Post post = schedule("hi", Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertEquals(0, dispatcher.dispatchDue());
        assertEquals(List.of(), publisher.calls);
        assertEquals(Post.Status.SCHEDULED, reload(post).status());
    }

    @Test
    void publishesAtThePublishTimeAndRecordsTheResult() {
        Post post = schedule("hi", Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertEquals(1, dispatcher.dispatchDue());

        Post done = reload(post);
        assertEquals(Post.Status.PUBLISHED, done.status());
        assertEquals("ext-1", done.externalId());
        assertEquals(clock.instant(), done.publishedAt());
        assertEquals(1, done.attempts());
        assertNull(done.nextAttemptAt());
    }

    @Test
    void publishesEachPostOnceEvenIfDispatchRunsAgain() {
        schedule("hi", Duration.ZERO);
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        assertEquals(1, publisher.calls.size());
    }

    @Test
    void retryableFailureIsRetriedWithExponentialBackoff() {
        publisher.thenFail(true).thenFail(true);
        Post post = schedule("hi", Duration.ZERO);

        dispatcher.dispatchDue(); // attempt 1 fails -> wait 1 s
        Post afterFirst = reload(post);
        assertEquals(Post.Status.SCHEDULED, afterFirst.status());
        assertEquals(1, afterFirst.attempts());
        assertEquals(T0.plusSeconds(1), afterFirst.nextAttemptAt());
        assertEquals("network said no", afterFirst.lastError());

        clock.advance(Duration.ofMillis(999));
        assertEquals(0, dispatcher.dispatchDue()); // not yet
        clock.advance(Duration.ofMillis(1));
        dispatcher.dispatchDue(); // attempt 2 fails -> wait 2 s
        assertEquals(clock.instant().plusSeconds(2), reload(post).nextAttemptAt());

        clock.advance(Duration.ofSeconds(2));
        dispatcher.dispatchDue(); // attempt 3 succeeds
        Post done = reload(post);
        assertEquals(Post.Status.PUBLISHED, done.status());
        assertEquals(3, done.attempts());
        assertNull(done.lastError());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        publisher.thenFail(true).thenFail(true).thenFail(true).thenFail(true);
        Post post = schedule("hi", Duration.ZERO);
        for (int i = 0; i < 10; i++) {
            dispatcher.dispatchDue();
            clock.advance(Duration.ofMinutes(1));
        }
        Post done = reload(post);
        assertEquals(Post.Status.FAILED, done.status());
        assertEquals(3, done.attempts());
        assertEquals(3, publisher.calls.size());
        assertEquals("network said no", done.lastError());
    }

    @Test
    void permanentFailureIsNotRetried() {
        publisher.thenFail(false);
        Post post = schedule("hi", Duration.ZERO);
        dispatcher.dispatchDue();
        clock.advance(Duration.ofHours(1));
        dispatcher.dispatchDue();

        assertEquals(Post.Status.FAILED, reload(post).status());
        assertEquals(1, publisher.calls.size());
    }

    @Test
    void unexpectedExceptionFromPublisherIsRetriedNotStuckInPublishing() {
        publisher.thenThrow(new IllegalStateException("bug in client"));
        Post post = schedule("hi", Duration.ZERO);
        dispatcher.dispatchDue();

        Post afterFailure = reload(post);
        assertEquals(Post.Status.SCHEDULED, afterFailure.status());
        assertEquals("unexpected error: java.lang.IllegalStateException: bug in client", afterFailure.lastError());

        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchDue();
        assertEquals(Post.Status.PUBLISHED, reload(post).status());
    }

    @Test
    void errorFromPublisherLeavesNoPostInPublishing() {
        publisher.thenThrow(new StackOverflowError("deep recursion in client"));
        Post first = schedule("first", Duration.ZERO);
        clock.advance(Duration.ofMillis(1));
        Post second = schedule("second", Duration.ZERO);

        assertThrows(StackOverflowError.class, dispatcher::dispatchDue);

        Post firstAfter = reload(first);
        assertEquals(Post.Status.SCHEDULED, firstAfter.status());
        assertEquals(1, firstAfter.attempts());
        assertEquals(clock.instant().plusSeconds(1), firstAfter.nextAttemptAt());
        assertEquals("unexpected error: java.lang.StackOverflowError: deep recursion in client", firstAfter.lastError());

        Post secondAfter = reload(second); // never reached: back to waiting, no attempt counted
        assertEquals(Post.Status.SCHEDULED, secondAfter.status());
        assertEquals(0, secondAfter.attempts());
        assertEquals(List.of(first.id()), publisher.calls);

        assertEquals(Post.Status.CANCELLED, store.cancel(second.id()).status()); // cancellable again
        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchDue();
        assertEquals(Post.Status.PUBLISHED, reload(first).status());
    }

    @Test
    void cancelledPostIsNeverPublished() {
        Post post = schedule("hi", Duration.ofSeconds(10));
        store.cancel(post.id());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, dispatcher.dispatchDue());
        assertEquals(List.of(), publisher.calls);
    }

    @Test
    void earliestDuePostIsPublishedFirst() {
        Post second = schedule("second", Duration.ofSeconds(2));
        Post first = schedule("first", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        dispatcher.dispatchDue();
        assertEquals(List.of(first.id(), second.id()), publisher.calls);
    }
}
