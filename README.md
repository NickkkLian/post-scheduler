# post-scheduler

A small Java 21 HTTP service that holds social media posts until their publish time, then publishes them, retrying failed attempts with exponential backoff.

Three posts sent to a real run, with the stand-in publisher set to fail 70% of the time (`SIMULATED_FAILURE_RATE=0.7`). Post `f572bbb2` waits 1, 2, 4, then 8 seconds between attempts and goes out on the fifth; post `845397cc` fails all five and is marked `FAILED`. These lines are copied unchanged from [sample/run-output.txt](sample/run-output.txt), which has the whole run:

```
2026-11-18T19:43:02-0700 INFO postscheduler.HttpApi - post f572bbb2-2840-41e5-9662-0d948d0fc86b scheduled on linkedin for 2026-11-19T02:43:02.009519Z
2026-11-18T19:43:02-0700 INFO postscheduler.Dispatcher - post f572bbb2-2840-41e5-9662-0d948d0fc86b attempt 1 failed (simulated timeout from linkedin); retry at 2026-11-19T02:43:03.587422Z
2026-11-18T19:43:03-0700 INFO postscheduler.Dispatcher - post f572bbb2-2840-41e5-9662-0d948d0fc86b attempt 2 failed (simulated timeout from linkedin); retry at 2026-11-19T02:43:05.592581Z
2026-11-18T19:43:05-0700 INFO postscheduler.Dispatcher - post f572bbb2-2840-41e5-9662-0d948d0fc86b attempt 3 failed (simulated timeout from linkedin); retry at 2026-11-19T02:43:09.603936Z
2026-11-18T19:43:09-0700 INFO postscheduler.Dispatcher - post f572bbb2-2840-41e5-9662-0d948d0fc86b attempt 4 failed (simulated timeout from linkedin); retry at 2026-11-19T02:43:17.625348Z
2026-11-18T19:43:17-0700 INFO postscheduler.Dispatcher - post f572bbb2-2840-41e5-9662-0d948d0fc86b published on linkedin as linkedin-481a666c (attempt 5)
2026-11-18T19:43:17-0700 WARNING postscheduler.Dispatcher - post 845397cc-870d-4a59-bc84-86628089696e failed after 5 attempts: simulated timeout from linkedin
```

Log times are local; times inside the messages are the API's UTC times.

## Run it

Needs JDK 21. The Maven wrapper downloads Maven itself.

```sh
./mvnw package                      # builds and runs the 48 tests
java -jar target/post-scheduler.jar # listens on :8080
curl -X POST localhost:8080/posts -d '{"channel":"linkedin","text":"Hello","publishAt":"2030-01-01T17:00:00Z"}'
```

## API

| Request | Result |
|---|---|
| `POST /posts` with `{"channel", "text", "publishAt"?}` | `201` and the post. No `publishAt` means publish now; at most 1825 days ahead. Optional `Idempotency-Key` header (1 to 255 characters). |
| `GET /posts/{id}` | The post, with `status`, `attempts`, `lastError`, `nextAttemptAt`, `externalId` as they apply. |
| `GET /posts?status=scheduled` | Posts in that status, ordered by publish time. Without `status`, all posts. |
| `DELETE /posts/{id}` | Cancels a post that is still `SCHEDULED`; `409` once publishing has started. |
| `GET /health` | `{"status":"ok"}` |

Statuses: `SCHEDULED` (waiting for its time or a retry), `PUBLISHING`, `PUBLISHED`, `FAILED`, `CANCELLED`.

Errors come back as `{"error": "..."}` with every validation problem listed, for example
`{"error":"text: required and must not be blank; publishAt: must not be in the past"}`.

## Design choices

- **The dispatcher claims posts under the store's lock** before publishing, so a post is never claimed twice: two publish calls for the same post never run at the same time, and a cancel cannot slip in after a publish has started. This is not "published at most once": a retry can publish a post twice (see "Exactly-once publishing" below).
- **Retryable and permanent failures are different.** A publisher marks an error as retryable (timeout, rate limit) or not (rejected content). Retryable errors wait `base * 2^(n-1)`, capped, for up to `MAX_ATTEMPTS` attempts; permanent ones fail at once. An unexpected exception from a publisher is treated as a retryable failure. If a JVM `Error` (such as `StackOverflowError`) escapes a publish call, the post being attempted is recorded as a failed retryable attempt and the rest of the claimed batch goes back to waiting before the error is passed on, so a publisher that throws cannot leave a post stuck in `PUBLISHING`.
- **Idempotency key on create.** A client that times out can resend the same request with the same key and get the original post back instead of a duplicate. The same key with a different body is a `409`.
- **Time is injected** (`java.time.Clock`), so the tests move a fake clock instead of sleeping. `Dispatcher.dispatchDue()` is one synchronous pass; the app runs it every `TICK_MS`.
- **Few dependencies:** the JDK's built-in HTTP server and Jackson for JSON. No web framework, to keep the code small enough to read in one sitting.
- Text length is counted in Unicode code points, not in what a reader sees as characters: 🚀 counts as one of the 280, but 👍🏽 (thumbs up plus a skin-tone modifier) counts as two, and flags or family emoji as several. Unknown JSON fields are rejected, so a typo like `publish_at` is an error rather than a silently ignored field, and so is a number or boolean where text is expected (`"text": 123`).

Settings are environment variables: `PORT` (8080), `TICK_MS` (1000), `BATCH_SIZE` (50), `MAX_ATTEMPTS` (5), `RETRY_BASE_MS` (1000), `RETRY_MAX_MS` (60000), `SIMULATED_FAILURE_RATE` (0). An unusable value (such as `BATCH_SIZE=0`) stops startup with a one-line `configuration error:` naming the variable.

## Tests

48 JUnit 5 tests (`./mvnw test`):

- `DispatcherTest`: nothing is published early; publish at the exact time; a second pass does not hand a post to the publisher again; backoff timings (1 s, then 2 s); giving up after the last attempt; permanent errors not retried; a publisher that throws an exception, or an `Error` with two posts claimed; cancelled posts skipped; earliest due first.
- `PostStoreTest`: idempotency (repeat, repeat after the publish time, conflict, key length), cancel rules, claim order and batch limit.
- `NewPostTest`, `RetryPolicyTest`, `ConfigTest`: validation edge cases (including how emoji are counted), the backoff formula including overflow at large attempt counts, and startup settings.
- `ApiEndToEndTest`: starts the real server and dispatch loop on a free port and drives it with `java.net.http.HttpClient`: schedule, publish when the clock reaches the time, retry after a failure, cancel, idempotent create, and every error status (400, 404, 405 with its `Allow` header, 409, 413).

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push.

## Left out on purpose

- **Persistence.** Posts live in memory and are lost on restart. A real version would store them in a database and claim due rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so that several instances never claim the same post; here that holds only within one process.
- **Real network APIs.** `SimulatedPublisher` stands in for them. A real publisher would map each network's error codes to retryable or permanent.
- **Exactly-once publishing.** A post can go out twice. The common case: a publish call times out after the network has already accepted the post; the timeout is retryable, so the retry publishes it again. A crash between the network accepting a post and the result being saved is not handled either; with a database that post would be stuck in `PUBLISHING` or published a second time. Avoiding duplicates needs the network's own idempotency support.
- Authentication, per-channel rate limits, jitter on retry delays, and metrics.

For production scheduling in Java, libraries such as db-scheduler or Quartz, or a queue with delayed delivery, already do this job. This repo is the core logic written out small enough to read and test.

## License

MIT, see [LICENSE](LICENSE).
