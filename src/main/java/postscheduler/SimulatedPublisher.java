package postscheduler;

import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Stands in for a real network API when running locally: it always succeeds, or fails with a retryable
 * error at the given rate so the retry path can be watched in the logs.
 */
final class SimulatedPublisher implements Publisher {

    private final double failureRate;
    private final RandomGenerator random;

    SimulatedPublisher(double failureRate, RandomGenerator random) {
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failure rate must be between 0 and 1");
        }
        this.failureRate = failureRate;
        this.random = random;
    }

    @Override
    public String publish(Post post) throws PublishException {
        if (random.nextDouble() < failureRate) {
            throw new PublishException("simulated timeout from " + post.channel(), true);
        }
        return post.channel() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
