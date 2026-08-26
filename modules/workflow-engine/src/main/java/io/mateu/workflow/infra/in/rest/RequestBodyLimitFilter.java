package io.mateu.workflow.infra.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The ceiling on how many bytes of body the engine's own HTTP endpoints will read from a caller.
 *
 * <p>{@code InputLimits} bounds what an event may <em>contain</em>, and the message controller
 * applies it — but only once the body has been read off the socket and parsed into an object. A
 * caller sending two hundred megabytes of JSON therefore spent two hundred megabytes of heap
 * before anything got to refuse it, on the request thread of a pod that is also running processes.
 * Refusing on content and refusing on size are different defences and both are needed: this one
 * exists so the first never has to be reached.
 *
 * <p>Deliberately narrow. It is registered on the engine's own paths only — the message API and the
 * git webhooks — and not application-wide, because this ships inside a library: a host application
 * whose own endpoint takes a large upload must not find it capped by a starter it added for workflow
 * orchestration.
 *
 * <p>{@code Content-Length} is checked first and answers most of it, since every ordinary client
 * sends one. It is a claim rather than a fact, though, and a chunked request has none at all, so the
 * body is also counted as it is read and abandoned the moment it passes the limit. Both answer
 * {@code 413 Payload Too Large}, which is the status that says "the request was understood and is
 * too big" — a caller can act on it without guessing.
 */
@Slf4j
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestBodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            refuse(request, response, declared + " bytes");
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
        } catch (RuntimeException | IOException | ServletException e) {
            // Whoever was reading the body — Jackson, a form parser — will have wrapped this in
            // something of its own, so the chain is walked rather than the exception matched.
            if (bodyWasTooLarge(e) && !response.isCommitted()) {
                refuse(request, response, "more than " + maxBytes + " bytes");
                return;
            }
            throw e;
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String size)
            throws IOException {
        log.warn("Refused {} {}: the request body is {}, over the {} byte limit",
                request.getMethod(), request.getRequestURI(), size, maxBytes);
        response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body is over the " + maxBytes + " byte limit");
    }

    private static boolean bodyWasTooLarge(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** Thrown from the body stream itself, which is the only place the real size is known. */
    static class BodyTooLargeException extends IOException {
        BodyTooLargeException(long maxBytes) {
            super("Request body is over the " + maxBytes + " byte limit");
        }
    }

    /** The request, with a body that stops rather than growing without end. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;
        private BufferedReader reader;

        private LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                var encoding = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        encoding == null ? StandardCharsets.UTF_8.name() : encoding));
            }
            return reader;
        }
    }

    /**
     * Counts what it hands out and refuses to hand out more than the limit. Every read path goes
     * through {@link #read()} or {@link #read(byte[], int, int)}, and the bulk one is overridden
     * rather than left to the single-byte default so a large body is not counted a byte at a time.
     */
    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        private LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            var b = delegate.read();
            if (b != -1 && ++read > maxBytes) {
                throw new BodyTooLargeException(maxBytes);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var count = delegate.read(buffer, offset, length);
            if (count > 0) {
                read += count;
                if (read > maxBytes) {
                    throw new BodyTooLargeException(maxBytes);
                }
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
