package postscheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The body of a create request, exactly as the client sent it.
 *
 * @param publishAt optional; when absent the post is due immediately
 */
public record NewPost(String channel, String text, Instant publishAt) {

    static final int MAX_TEXT_LENGTH = 280;
    /** How far ahead a post may be scheduled; mainly to reject nonsense such as the year 1000000000. */
    static final Duration MAX_LEAD_TIME = Duration.ofDays(5 * 365);
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /**
     * Returns every problem with this request, or an empty list when it is valid.
     * Text length is counted in Unicode code points: a single-code-point emoji such as a rocket counts as one,
     * but an emoji built from several code points (a thumbs-up with a skin tone, a flag) counts as several.
     */
    List<String> problems(Instant now) {
        List<String> problems = new ArrayList<>();
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            problems.add("channel: required, 1 to 40 lowercase letters, digits or '-', starting with a letter or digit");
        }
        if (text == null || text.isBlank()) {
            problems.add("text: required and must not be blank");
        } else if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            problems.add("text: at most " + MAX_TEXT_LENGTH + " characters");
        }
        if (publishAt != null && publishAt.isBefore(now)) {
            problems.add("publishAt: must not be in the past");
        } else if (publishAt != null && publishAt.isAfter(now.plus(MAX_LEAD_TIME))) {
            problems.add("publishAt: at most " + MAX_LEAD_TIME.toDays() + " days ahead");
        }
        return problems;
    }
}
