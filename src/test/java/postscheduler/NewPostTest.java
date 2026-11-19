package postscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static postscheduler.TestSupport.T0;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewPostTest {

    @Test
    void validRequestHasNoProblems() {
        assertEquals(List.of(), new NewPost("linkedin", "Hello", T0.plusSeconds(60)).problems(T0));
    }

    @Test
    void publishTimeIsOptionalAndMayBeExactlyNow() {
        assertEquals(List.of(), new NewPost("x", "Hello", null).problems(T0));
        assertEquals(List.of(), new NewPost("x", "Hello", T0).problems(T0));
    }

    @Test
    void publishTimeInThePastIsRejected() {
        assertEquals(List.of("publishAt: must not be in the past"),
                new NewPost("x", "Hello", T0.minusMillis(1)).problems(T0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "LinkedIn", "-leading-dash", "has space", "a-name-that-is-forty-one-characters-long1"})
    void badChannelNamesAreRejected(String channel) {
        List<String> problems = new NewPost(channel, "Hello", null).problems(T0);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("channel:"), problems.get(0));
    }

    @Test
    void textLimitCountsCodePointsSoEmojiCountOnce() {
        String emoji = "🚀"; // one code point, two Java chars
        assertEquals(List.of(), new NewPost("x", emoji.repeat(280), null).problems(T0));
        assertEquals(List.of("text: at most 280 characters"), new NewPost("x", emoji.repeat(281), null).problems(T0));
    }

    @Test
    void emojiMadeOfSeveralCodePointsCountsAsSeveral() {
        String thumbsUpMediumSkinTone = "\uD83D\uDC4D\uD83C\uDFFD"; // U+1F44D U+1F3FD
        assertEquals(List.of(), new NewPost("x", thumbsUpMediumSkinTone.repeat(140), null).problems(T0));
        assertEquals(List.of("text: at most 280 characters"),
                new NewPost("x", thumbsUpMediumSkinTone.repeat(141), null).problems(T0));
    }

    @Test
    void publishTimeMoreThanFiveYearsAheadIsRejected() {
        assertEquals(List.of(), new NewPost("x", "Hello", T0.plus(Duration.ofDays(1825))).problems(T0));
        assertEquals(List.of("publishAt: at most 1825 days ahead"),
                new NewPost("x", "Hello", T0.plus(Duration.ofDays(1825)).plusSeconds(1)).problems(T0));
        assertEquals(List.of("publishAt: at most 1825 days ahead"),
                new NewPost("x", "Hello", Instant.MAX).problems(T0));
    }

    @Test
    void blankTextIsRejected() {
        assertEquals(List.of("text: required and must not be blank"), new NewPost("x", "  \n", null).problems(T0));
        assertEquals(List.of("text: required and must not be blank"), new NewPost("x", null, null).problems(T0));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertEquals(3, new NewPost(null, null, T0.minusSeconds(1)).problems(T0).size());
    }
}
