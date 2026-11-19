package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static postscheduler.TestSupport.T0;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostStoreTest {

    private final PostStore store = new PostStore();

    @Test
    void createWithoutPublishTimeIsDueNow() {
        Post post = store.create(new NewPost("x", "hi", null), null, T0).post();
        assertEquals(T0, post.publishAt());
        assertEquals(Post.Status.SCHEDULED, post.status());
        assertEquals(0, post.attempts());
    }

    @Test
    void invalidRequestIsA400WithEveryProblem() {
        ApiException e = assertThrows(ApiException.class, () -> store.create(new NewPost("", "", null), null, T0));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("channel:") && e.getMessage().contains("text:"), e.getMessage());
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsTheFirstPost() {
        NewPost request = new NewPost("x", "hi", T0.plusSeconds(60));
        PostStore.CreateResult first = store.create(request, "key-1", T0);
        PostStore.CreateResult again = store.create(request, "key-1", T0.plusSeconds(1));
        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.post().id(), again.post().id());
        assertEquals(1, store.list(Optional.empty()).size());
    }

    @Test
    void retryWithSameKeyStillWorksAfterThePublishTimeHasPassed() {
        NewPost request = new NewPost("x", "hi", T0.plusSeconds(60));
        String id = store.create(request, "key-1", T0).post().id();
        assertEquals(id, store.create(request, "key-1", T0.plusSeconds(120)).post().id());
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIsAConflict() {
        store.create(new NewPost("x", "hi", null), "key-1", T0);
        ApiException e = assertThrows(ApiException.class,
                () -> store.create(new NewPost("x", "different", null), "key-1", T0));
        assertEquals(409, e.status);
    }

    @Test
    void emptyOrOverlongIdempotencyKeyIsRejected() {
        NewPost request = new NewPost("x", "hi", null);
        assertEquals(400, assertThrows(ApiException.class, () -> store.create(request, "", T0)).status);
        assertEquals(400, assertThrows(ApiException.class,
                () -> store.create(request, "k".repeat(PostStore.MAX_KEY_LENGTH + 1), T0)).status);
        assertTrue(store.create(request, "k".repeat(PostStore.MAX_KEY_LENGTH), T0).created());
        assertEquals(List.of(), store.list(Optional.of(Post.Status.CANCELLED)));
    }

    @Test
    void listFiltersByStatusAndSortsByPublishTime() {
        Post later = store.create(new NewPost("x", "later", T0.plusSeconds(300)), null, T0).post();
        Post sooner = store.create(new NewPost("x", "sooner", T0.plusSeconds(100)), null, T0).post();
        Post gone = store.create(new NewPost("x", "gone", T0.plusSeconds(200)), null, T0).post();
        store.cancel(gone.id());

        assertEquals(List.of(sooner.id(), gone.id(), later.id()), ids(store.list(Optional.empty())));
        assertEquals(List.of(sooner.id(), later.id()), ids(store.list(Optional.of(Post.Status.SCHEDULED))));
        assertEquals(List.of(gone.id()), ids(store.list(Optional.of(Post.Status.CANCELLED))));
    }

    @Test
    void cancelUnknownIdIs404AndCancelTwiceIs409() {
        assertEquals(404, assertThrows(ApiException.class, () -> store.cancel("nope")).status);
        Post post = store.create(new NewPost("x", "hi", null), null, T0).post();
        assertEquals(Post.Status.CANCELLED, store.cancel(post.id()).status());
        assertEquals(409, assertThrows(ApiException.class, () -> store.cancel(post.id())).status);
    }

    @Test
    void claimHandsOutOnlyDuePostsEarliestFirstAndOnlyOnce() {
        Post b = store.create(new NewPost("x", "b", T0.plusSeconds(20)), null, T0).post();
        Post a = store.create(new NewPost("x", "a", T0.plusSeconds(10)), null, T0).post();
        store.create(new NewPost("x", "future", T0.plusSeconds(999)), null, T0);

        List<Post> claimed = store.claimDue(T0.plusSeconds(20), 10);
        assertEquals(List.of(a.id(), b.id()), ids(claimed));
        assertTrue(claimed.stream().allMatch(p -> p.status() == Post.Status.PUBLISHING));
        assertEquals(List.of(), store.claimDue(T0.plusSeconds(20), 10));
    }

    @Test
    void claimRespectsTheBatchLimit() {
        for (int i = 0; i < 5; i++) {
            store.create(new NewPost("x", "p" + i, null), null, T0);
        }
        assertEquals(2, store.claimDue(T0, 2).size());
        assertEquals(3, store.claimDue(T0, 10).size());
    }

    @Test
    void aClaimedPostCannotBeCancelled() {
        Post post = store.create(new NewPost("x", "hi", null), null, T0).post();
        store.claimDue(T0, 10);
        assertEquals(409, assertThrows(ApiException.class, () -> store.cancel(post.id())).status);
    }

    private static List<String> ids(List<Post> posts) {
        return posts.stream().map(Post::id).toList();
    }
}
