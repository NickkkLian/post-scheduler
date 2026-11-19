package postscheduler;

/** Sends a post to a social network. Implementations talk to one network's API. */
public interface Publisher {

    /**
     * Publishes the post and returns the id the network gave it.
     *
     * @throws PublishException when the network refuses or cannot be reached
     */
    String publish(Post post) throws PublishException;

    /** A failed publish. {@code retryable} is false for errors a retry cannot fix, such as rejected content. */
    final class PublishException extends Exception {

        private static final long serialVersionUID = 1L;

        private final boolean retryable;

        public PublishException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
