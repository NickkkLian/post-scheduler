package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static postscheduler.TestSupport.T0;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import postscheduler.TestSupport.MutableClock;
import postscheduler.TestSupport.ScriptedPublisher;

/**
 * Starts the real app (HTTP server and dispatch loop on a 20 ms tick) on a free port and drives it over HTTP.
 * The clock is fake, so "publish in an hour" is tested by moving the clock, not by waiting.
 */
class ApiEndToEndTest {

    private final MutableClock clock = new MutableClock(T0);
    private final ScriptedPublisher publisher = new ScriptedPublisher();
    private final HttpClient http = HttpClient.newHttpClient();
    private App app;

    @BeforeEach
    void start() throws IOException {
        App.Config config = new App.Config(0, Duration.ofMillis(20), 50,
                new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(5)), 0);
        app = App.start(config, clock, publisher);
    }

    @AfterEach
    void stop() {
        app.close();
    }

    @Test
    void scheduledPostIsPublishedWhenItsTimeComesAndCanNoLongerBeCancelled() throws Exception {
        HttpResponse<String> created = post("/posts",
                "{\"channel\":\"linkedin\",\"text\":\"Launch day\",\"publishAt\":\"2026-01-01T10:00:00Z\"}", null);
        assertEquals(201, created.statusCode());
        JsonNode body = json(created);
        String id = body.get("id").asText();
        assertEquals("/posts/" + id, created.headers().firstValue("Location").orElseThrow());
        assertEquals("SCHEDULED", body.get("status").asText());

        Thread.sleep(100); // several ticks pass; it is not due yet
        assertEquals("SCHEDULED", json(get("/posts/" + id)).get("status").asText());
        assertEquals(0, publisher.calls.size());

        clock.advance(Duration.ofHours(1));
        JsonNode published = awaitPost(id, p -> p.get("status").asText().equals("PUBLISHED"));
        assertEquals("ext-1", published.get("externalId").asText());
        assertEquals("2026-01-01T10:00:00Z", published.get("publishedAt").asText());

        assertEquals(409, send("DELETE", "/posts/" + id, null, null).statusCode());
        JsonNode listed = json(get("/posts?status=published"));
        assertEquals(1, listed.size());
        assertEquals(id, listed.get(0).get("id").asText());
    }

    @Test
    void failedAttemptIsRetriedAfterTheBackoff() throws Exception {
        publisher.thenFail(true);
        String id = json(post("/posts", "{\"channel\":\"x\",\"text\":\"retry me\"}", null)).get("id").asText();

        JsonNode waiting = awaitPost(id, p -> p.get("attempts").asInt() == 1);
        assertEquals("SCHEDULED", waiting.get("status").asText());
        assertEquals("network said no", waiting.get("lastError").asText());
        assertEquals("2026-01-01T09:00:30Z", waiting.get("nextAttemptAt").asText());

        clock.advance(Duration.ofSeconds(30));
        JsonNode published = awaitPost(id, p -> p.get("status").asText().equals("PUBLISHED"));
        assertEquals(2, published.get("attempts").asInt());
    }

    @Test
    void cancelledPostIsNotPublished() throws Exception {
        String id = json(post("/posts",
                "{\"channel\":\"x\",\"text\":\"never mind\",\"publishAt\":\"2026-01-01T09:05:00Z\"}", null))
                .get("id").asText();
        HttpResponse<String> cancelled = send("DELETE", "/posts/" + id, null, null);
        assertEquals(200, cancelled.statusCode());
        assertEquals("CANCELLED", json(cancelled).get("status").asText());

        clock.advance(Duration.ofMinutes(10));
        Thread.sleep(100);
        assertEquals("CANCELLED", json(get("/posts/" + id)).get("status").asText());
        assertEquals(0, publisher.calls.size());
    }

    @Test
    void idempotencyKeyMakesRetriedCreatesSafe() throws Exception {
        String body = "{\"channel\":\"x\",\"text\":\"once\",\"publishAt\":\"2026-01-01T12:00:00Z\"}";
        HttpResponse<String> first = post("/posts", body, "abc-123");
        HttpResponse<String> repeat = post("/posts", body, "abc-123");
        assertEquals(201, first.statusCode());
        assertEquals(200, repeat.statusCode());
        assertEquals(json(first).get("id"), json(repeat).get("id"));
        assertEquals(1, json(get("/posts")).size());

        HttpResponse<String> clash = post("/posts", "{\"channel\":\"x\",\"text\":\"other\"}", "abc-123");
        assertEquals(409, clash.statusCode());
    }

    @Test
    void badRequestsGetClearErrors() throws Exception {
        assertError(post("/posts", "{not json", null), 400, "invalid JSON");
        assertError(post("/posts", "", null), 400, "invalid JSON");
        assertError(post("/posts", "{\"channel\":\"x\",\"text\":\"t\",\"publish_at\":\"soon\"}", null), 400, "publish_at");
        assertError(post("/posts", "{\"channel\":\"x\",\"text\":\"old\",\"publishAt\":\"2025-12-31T00:00:00Z\"}", null),
                400, "publishAt: must not be in the past");
        assertError(post("/posts", "{\"channel\":\"x\",\"text\":\"" + "a".repeat(HttpApi.MAX_BODY_BYTES) + "\"}", null),
                413, "larger than");
        assertError(get("/posts/does-not-exist"), 404, "no post with id");
        assertError(get("/posts?status=sent"), 400, "unknown status");
        assertError(get("/nothing-here"), 404, "no such endpoint");
        HttpResponse<String> putPosts = send("PUT", "/posts", "{}", null);
        assertError(putPosts, 405, "allowed methods: GET, POST");
        assertEquals("GET, POST", putPosts.headers().firstValue("Allow").orElse("(none)"));
        HttpResponse<String> postOnePost = send("POST", "/posts/some-id", "{}", null);
        assertError(postOnePost, 405, "allowed methods: GET, DELETE");
        assertEquals("GET, DELETE", postOnePost.headers().firstValue("Allow").orElse("(none)"));
        assertEquals("GET", send("DELETE", "/health", null, null).headers().firstValue("Allow").orElse("(none)"));
        assertError(post("/posts", "{\"channel\":\"x\",\"text\":123}", null), 400, "invalid JSON");
        assertError(post("/posts", "{\"channel\":true,\"text\":\"t\"}", null), 400, "invalid JSON");
        assertError(post("/posts", "{\"channel\":\"x\",\"text\":\"t\"}", "k".repeat(PostStore.MAX_KEY_LENGTH + 1)),
                400, "Idempotency-Key");
        assertEquals(200, get("/health").statusCode());
    }

    private void assertError(HttpResponse<String> response, int status, String messagePart) throws IOException {
        assertEquals(status, response.statusCode(), response.body());
        String error = json(response).get("error").asText();
        assertTrue(error.contains(messagePart), error);
    }

    private JsonNode awaitPost(String id, Predicate<JsonNode> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = json(get("/posts/" + id));
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(20);
        }
        return fail("condition not met within 5 s; last state: " + last);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey) throws Exception {
        return send("POST", path, body, idempotencyKey);
    }

    private HttpResponse<String> send(String method, String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return HttpApi.JSON.readTree(response.body());
    }
}
