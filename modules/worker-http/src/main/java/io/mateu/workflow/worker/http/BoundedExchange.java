package io.mateu.workflow.worker.http;

import io.mateu.workflow.worker.api.TaskFailure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An HTTP exchange bounded in time and size. {@link HttpRequest#timeout()} only covers the response
 * headers; here one deadline covers the headers and the whole body, and past it (or past the size cap)
 * the exchange is cancelled, which releases its connection.
 */
final class BoundedExchange {

    private BoundedExchange() {
    }

    /**
     * Sends {@code request} and reads its body as UTF-8 text within {@code timeout}. Fails with
     * {@link HttpTimeoutException} past the deadline, an {@link IOException} on any I/O error (sending or
     * reading the body), and {@code HTTP_RESPONSE_TOO_LARGE} — reason {@code tooLarge} — past {@code max}
     * bytes, without buffering more than that.
     */
    static HttpResponse<String> send(HttpClient client, HttpRequest request, Duration timeout, long max,
                                     String tooLarge) throws IOException {
        var future = client.sendAsync(request, info -> new CappedBody(max));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("no complete response within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            IOException io = null;
            for (var cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseTooLarge) {
                    throw new TaskFailure("HTTP_RESPONSE_TOO_LARGE", tooLarge, false);
                }
                if (io == null && cause instanceof IOException found) {
                    io = found;
                }
            }
            throw io != null ? io : new IOException(e.getCause());
        }
    }

    /** Past the cap the exchange is cancelled: nothing beyond {@code max} bytes is buffered. */
    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(long max) {
            super("the response is larger than " + max + " bytes");
        }
    }

    /** Collects the body as UTF-8 text, up to {@code max} bytes. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<String> {
        private final long max;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(long max) {
            this.max = max;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (var buffer : buffers) {
                if (bytes.size() + (long) buffer.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge(max));
                    return;
                }
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
