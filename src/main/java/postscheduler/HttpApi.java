package postscheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * JSON over HTTP, on the JDK's built-in server.
 *
 * <pre>
 * POST   /posts                create (optional Idempotency-Key header)
 * GET    /posts[?status=X]     list, ordered by publish time
 * GET    /posts/{id}           one post
 * DELETE /posts/{id}           cancel a SCHEDULED post
 * GET    /health               liveness
 * </pre>
 */
final class HttpApi implements HttpHandler {

    private static final Logger LOG = System.getLogger(HttpApi.class.getName());
    static final int MAX_BODY_BYTES = 64 * 1024;

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));

    static {
        // By default Jackson turns {"text": 123} into "123". A number or boolean where text is expected is a client
        // bug, so reject it.
        for (CoercionInputShape shape : new CoercionInputShape[] {
                CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
    }

    private final PostStore store;
    private final Clock clock;

    HttpApi(PostStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (ApiException e) {
                if (e.allow != null) {
                    exchange.getResponseHeaders().set("Allow", e.allow);
                }
                send(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, exchange.getRequestMethod() + " " + exchange.getRequestURI() + " failed", e);
                send(exchange, 500, Map.of("error", "internal error"));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/health")) {
            if (!method.equals("GET")) {
                throw ApiException.methodNotAllowed("GET");
            }
            send(exchange, 200, Map.of("status", "ok"));
        } else if (path.equals("/posts") || path.equals("/posts/")) {
            switch (method) {
                case "POST" -> create(exchange);
                case "GET" -> send(exchange, 200, store.list(statusFilter(exchange.getRequestURI().getQuery())));
                default -> throw ApiException.methodNotAllowed("GET, POST");
            }
        } else if (path.startsWith("/posts/") && path.indexOf('/', "/posts/".length()) < 0) {
            String id = path.substring("/posts/".length());
            switch (method) {
                case "GET" -> send(exchange, 200,
                        store.get(id).orElseThrow(() -> ApiException.notFound("no post with id " + id)));
                case "DELETE" -> send(exchange, 200, store.cancel(id));
                default -> throw ApiException.methodNotAllowed("GET, DELETE");
            }
        } else {
            throw ApiException.notFound("no such endpoint: " + path);
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        NewPost request;
        try {
            request = JSON.readValue(readBody(exchange), NewPost.class);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("invalid JSON: " + e.getOriginalMessage());
        }
        if (request == null) {
            throw ApiException.badRequest("request body is empty");
        }
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        PostStore.CreateResult result = store.create(request, key, clock.instant());
        if (result.created()) {
            LOG.log(Level.INFO, "post {0} scheduled on {1} for {2}",
                    result.post().id(), result.post().channel(), result.post().publishAt());
            exchange.getResponseHeaders().set("Location", "/posts/" + result.post().id());
            send(exchange, 201, result.post());
        } else {
            send(exchange, 200, result.post());
        }
    }

    private static Optional<Post.Status> statusFilter(String query) {
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }
        if (!query.startsWith("status=") || query.contains("&")) {
            throw ApiException.badRequest("the only supported query is ?status=<status>");
        }
        String value = query.substring("status=".length());
        try {
            return Optional.of(Post.Status.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown status: " + value);
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new ApiException(413, "request body is larger than " + MAX_BODY_BYTES + " bytes");
            }
            return body;
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
