package postscheduler;

import java.time.Instant;

/**
 * A scheduled post and where it is in its life cycle. Immutable: every state change makes a new copy.
 *
 * @param attempts      publish attempts made so far
 * @param nextAttemptAt when the dispatcher may next try to publish it (the publish time, or a retry time)
 * @param lastError     message from the most recent failed attempt, or null
 * @param externalId    id the publisher returned on success, or null
 */
public record Post(
        String id,
        String channel,
        String text,
        Instant publishAt,
        Status status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant publishedAt,
        String externalId) {

    public enum Status {
        /** Waiting for its publish time or for a retry. */
        SCHEDULED,
        /** Claimed by the dispatcher; a publish call is in flight. */
        PUBLISHING,
        PUBLISHED,
        /** Gave up: a permanent error, or out of attempts. */
        FAILED,
        CANCELLED
    }

    static Post scheduled(String id, String channel, String text, Instant publishAt) {
        return new Post(id, channel, text, publishAt, Status.SCHEDULED, 0, publishAt, null, null, null);
    }

    Post claimed() {
        return new Post(id, channel, text, publishAt, Status.PUBLISHING, attempts, nextAttemptAt, lastError, null, null);
    }

    Post published(Instant at, String externalId) {
        return new Post(id, channel, text, publishAt, Status.PUBLISHED, attempts + 1, null, null, at, externalId);
    }

    Post retryAt(Instant at, String error) {
        return new Post(id, channel, text, publishAt, Status.SCHEDULED, attempts + 1, at, error, null, null);
    }

    /** Back to waiting, unchanged, for a claimed post that was never attempted. */
    Post released() {
        return new Post(id, channel, text, publishAt, Status.SCHEDULED, attempts, nextAttemptAt, lastError, null, null);
    }

    Post failed(String error) {
        return new Post(id, channel, text, publishAt, Status.FAILED, attempts + 1, null, error, null, null);
    }

    Post cancelled() {
        return new Post(id, channel, text, publishAt, Status.CANCELLED, attempts, null, lastError, null, null);
    }
}
