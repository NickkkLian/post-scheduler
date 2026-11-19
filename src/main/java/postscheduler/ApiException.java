package postscheduler;

/** An error that maps to one HTTP status code and a message safe to show the client. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    final int status;
    /** Value for the Allow header on a 405, as RFC 9110 requires; null otherwise. */
    final String allow;

    ApiException(int status, String message) {
        this(status, message, null);
    }

    private ApiException(int status, String message, String allow) {
        super(message);
        this.status = status;
        this.allow = allow;
    }

    static ApiException methodNotAllowed(String allowedMethods) {
        return new ApiException(405, "allowed methods: " + allowedMethods, allowedMethods);
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
