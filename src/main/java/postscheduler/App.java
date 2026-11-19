package postscheduler;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/** Wires the store, dispatcher and HTTP server together, and reads settings from environment variables. */
public final class App implements AutoCloseable {

    private static final Logger LOG = System.getLogger(App.class.getName());

    /** Settings; every field has an environment variable of the same name in upper snake case. */
    public record Config(int port, Duration tick, int batchSize, RetryPolicy retryPolicy, double simulatedFailureRate) {

        public Config {
            if (tick.toMillis() < 1) {
                throw new IllegalArgumentException("TICK_MS must be at least 1, got " + tick.toMillis());
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("BATCH_SIZE must be at least 1, got " + batchSize);
            }
            if (!(simulatedFailureRate >= 0 && simulatedFailureRate <= 1)) {
                throw new IllegalArgumentException(
                        "SIMULATED_FAILURE_RATE must be between 0 and 1, got " + simulatedFailureRate);
            }
        }

        /** Reads settings; throws IllegalArgumentException naming the variable when a value is unusable. */
        static Config fromEnv(Map<String, String> env) {
            int retryBaseMs = intVar(env, "RETRY_BASE_MS", 1000, 1, Integer.MAX_VALUE);
            int retryMaxMs = intVar(env, "RETRY_MAX_MS", 60_000, 1, Integer.MAX_VALUE);
            if (retryBaseMs > retryMaxMs) {
                throw new IllegalArgumentException("RETRY_BASE_MS (" + retryBaseMs
                        + ") must not be larger than RETRY_MAX_MS (" + retryMaxMs + ")");
            }
            return new Config(
                    intVar(env, "PORT", 8080, 1, 65_535),
                    Duration.ofMillis(intVar(env, "TICK_MS", 1000, 1, Integer.MAX_VALUE)),
                    intVar(env, "BATCH_SIZE", 50, 1, Integer.MAX_VALUE),
                    new RetryPolicy(
                            intVar(env, "MAX_ATTEMPTS", 5, 1, Integer.MAX_VALUE),
                            Duration.ofMillis(retryBaseMs),
                            Duration.ofMillis(retryMaxMs)),
                    doubleVar(env, "SIMULATED_FAILURE_RATE", 0));
        }

        private static int intVar(Map<String, String> env, String name, int fallback, int min, int max) {
            String value = env.get(name);
            int parsed;
            try {
                parsed = value == null ? fallback : Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number, got: " + value, e);
            }
            if (parsed < min) {
                throw new IllegalArgumentException(name + " must be at least " + min + ", got " + parsed);
            }
            if (parsed > max) {
                throw new IllegalArgumentException(name + " must be at most " + max + ", got " + parsed);
            }
            return parsed;
        }

        private static double doubleVar(Map<String, String> env, String name, double fallback) {
            String value = env.get(name);
            try {
                return value == null ? fallback : Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number, got: " + value, e);
            }
        }
    }

    private final HttpServer server;
    private final ScheduledExecutorService ticker;

    private App(HttpServer server, ScheduledExecutorService ticker) {
        this.server = server;
        this.ticker = ticker;
    }

    /** Starts the HTTP server and the dispatch loop. Port 0 picks a free port; see {@link #port()}. */
    public static App start(Config config, Clock clock, Publisher publisher) throws IOException {
        PostStore store = new PostStore();
        Dispatcher dispatcher = new Dispatcher(store, publisher, config.retryPolicy(), clock, config.batchSize());

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/", new HttpApi(store, clock));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dispatcher");
            t.setDaemon(true);
            return t;
        });
        long tickMs = config.tick().toMillis();
        ticker.scheduleWithFixedDelay(() -> {
            // An exception escaping here would silently cancel every future run, so catch everything.
            try {
                dispatcher.dispatchDue();
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "dispatch pass failed", t);
            }
        }, tickMs, tickMs, TimeUnit.MILLISECONDS);

        return new App(server, ticker);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        server.stop(0);
    }

    public static void main(String[] args) throws IOException {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tFT%1$tT%1$tz %4$s %3$s - %5$s%6$s%n");
        }
        Config config;
        try {
            config = Config.fromEnv(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        App app = start(config, Clock.systemUTC(),
                new SimulatedPublisher(config.simulatedFailureRate(), RandomGenerator.getDefault()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        LOG.log(Level.INFO, "listening on port {0}, checking for due posts every {1} ms",
                String.valueOf(app.port()), String.valueOf(config.tick().toMillis()));
    }
}
