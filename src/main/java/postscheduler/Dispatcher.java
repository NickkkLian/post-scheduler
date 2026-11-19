package postscheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Publishes due posts. {@link #dispatchDue()} does one pass and is what the tests call directly; the app
 * calls it on a fixed delay.
 */
public final class Dispatcher {

    private static final Logger LOG = System.getLogger(Dispatcher.class.getName());

    private final PostStore store;
    private final Publisher publisher;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final int batchSize;

    public Dispatcher(PostStore store, Publisher publisher, RetryPolicy retryPolicy, Clock clock, int batchSize) {
        this.store = store;
        this.publisher = publisher;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Publishes every post that is due now (up to the batch size) and returns how many it attempted. */
    public int dispatchDue() {
        List<Post> due = store.claimDue(clock.instant(), batchSize);
        int saved = 0;
        try {
            for (Post post : due) {
                store.save(attempt(post));
                saved++;
            }
        } catch (Error e) {
            // An Error (StackOverflowError, OutOfMemoryError ...) is not caught in attempt(). Before passing it on,
            // un-claim the batch so no post is left in PUBLISHING: the post being attempted counts as a failed,
            // retryable attempt, and the ones not reached yet go back to waiting unchanged.
            Post current = due.get(saved);
            store.save(afterFailure(current, current.attempts() + 1, "unexpected error: " + e, true));
            for (Post notReached : due.subList(saved + 1, due.size())) {
                store.save(notReached.released());
            }
            throw e;
        }
        return due.size();
    }

    private Post attempt(Post post) {
        int attemptNumber = post.attempts() + 1;
        try {
            String externalId = publisher.publish(post);
            LOG.log(Level.INFO, "post {0} published on {1} as {2} (attempt {3})",
                    post.id(), post.channel(), externalId, attemptNumber);
            return post.published(clock.instant(), externalId);
        } catch (Publisher.PublishException e) {
            return afterFailure(post, attemptNumber, e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            // A bug or an unexpected client error in a publisher must not leave the post stuck in PUBLISHING.
            LOG.log(Level.ERROR, "post " + post.id() + ": publisher threw unexpectedly", e);
            return afterFailure(post, attemptNumber, "unexpected error: " + e, true);
        }
    }

    private Post afterFailure(Post post, int attemptNumber, String error, boolean retryable) {
        if (!retryable) {
            LOG.log(Level.WARNING, "post {0} failed permanently on attempt {1}: {2}", post.id(), attemptNumber, error);
            return post.failed(error);
        }
        if (retryPolicy.exhausted(attemptNumber)) {
            LOG.log(Level.WARNING, "post {0} failed after {1} attempts: {2}", post.id(), attemptNumber, error);
            return post.failed(error);
        }
        Instant next = clock.instant().plus(retryPolicy.delayAfter(attemptNumber));
        LOG.log(Level.INFO, "post {0} attempt {1} failed ({2}); retry at {3}", post.id(), attemptNumber, error, next);
        return post.retryAt(next, error);
    }
}
