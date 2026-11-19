package postscheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory store of posts. All methods are synchronized: the HTTP threads and the dispatcher share it,
 * and every operation is a short map read or write, so one lock is simpler than finer-grained locking.
 */
public final class PostStore {

    /** Result of a create: the post, and whether this call made it or found it by idempotency key. */
    public record CreateResult(Post post, boolean created) {}

    private record KeyedRequest(NewPost request, String postId) {}

    private static final Comparator<Post> BY_DUE_TIME =
            Comparator.comparing(Post::nextAttemptAt).thenComparing(Post::id);
    private static final Comparator<Post> BY_PUBLISH_TIME =
            Comparator.comparing(Post::publishAt).thenComparing(Post::id);

    /** Longest Idempotency-Key accepted; keys are kept in memory, so they must not be unbounded. */
    static final int MAX_KEY_LENGTH = 255;

    private final Map<String, Post> posts = new HashMap<>();
    private final Map<String, KeyedRequest> byIdempotencyKey = new HashMap<>();

    /**
     * Creates a post. With an idempotency key, a repeat of the same request returns the post made the first
     * time (so a client can safely retry after a timeout), and a different request under the same key is a
     * conflict. The key is checked before validation, so a retry still succeeds after its publish time passes.
     */
    public synchronized CreateResult create(NewPost request, String idempotencyKey, Instant now) {
        if (idempotencyKey != null) {
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > MAX_KEY_LENGTH) {
                throw ApiException.badRequest("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
            }
            KeyedRequest previous = byIdempotencyKey.get(idempotencyKey);
            if (previous != null) {
                if (!previous.request().equals(request)) {
                    throw ApiException.conflict("Idempotency-Key was already used with a different request");
                }
                return new CreateResult(posts.get(previous.postId()), false);
            }
        }
        List<String> problems = request.problems(now);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", problems));
        }
        Instant publishAt = request.publishAt() != null ? request.publishAt() : now;
        Post post = Post.scheduled(UUID.randomUUID().toString(), request.channel(), request.text(), publishAt);
        posts.put(post.id(), post);
        if (idempotencyKey != null) {
            byIdempotencyKey.put(idempotencyKey, new KeyedRequest(request, post.id()));
        }
        return new CreateResult(post, true);
    }

    public synchronized Optional<Post> get(String id) {
        return Optional.ofNullable(posts.get(id));
    }

    /** All posts, or only those with the given status, ordered by publish time. */
    public synchronized List<Post> list(Optional<Post.Status> status) {
        return posts.values().stream()
                .filter(p -> status.isEmpty() || p.status() == status.get())
                .sorted(BY_PUBLISH_TIME)
                .toList();
    }

    /** Cancels a post that has not been picked up yet. */
    public synchronized Post cancel(String id) {
        Post post = get(id).orElseThrow(() -> ApiException.notFound("no post with id " + id));
        if (post.status() != Post.Status.SCHEDULED) {
            throw ApiException.conflict("only a SCHEDULED post can be cancelled; this one is " + post.status());
        }
        Post cancelled = post.cancelled();
        posts.put(id, cancelled);
        return cancelled;
    }

    /**
     * Marks up to {@code limit} due posts as PUBLISHING and returns them, earliest first. Because claiming
     * happens under the lock, a post is handed out once, and a cancel cannot slip in after it is claimed.
     */
    synchronized List<Post> claimDue(Instant now, int limit) {
        List<Post> due = posts.values().stream()
                .filter(p -> p.status() == Post.Status.SCHEDULED && !p.nextAttemptAt().isAfter(now))
                .sorted(BY_DUE_TIME)
                .limit(limit)
                .toList();
        List<Post> claimed = new ArrayList<>(due.size());
        for (Post post : due) {
            Post c = post.claimed();
            posts.put(c.id(), c);
            claimed.add(c);
        }
        return claimed;
    }

    /** Records the outcome of a publish attempt for a claimed post. */
    synchronized void save(Post post) {
        posts.put(post.id(), post);
    }
}
