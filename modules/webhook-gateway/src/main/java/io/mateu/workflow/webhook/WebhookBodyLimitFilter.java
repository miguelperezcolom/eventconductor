package io.mateu.workflow.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The ceiling on a webhook body ({@code workflow.webhooks.max-body-bytes}, 25 MB by default), applied
 * on the gateway's path before the controller binds the body into a {@code byte[]}: a sender — or
 * anybody who can reach the endpoint, since the signature can only be checked once the body is read —
 * cannot make the gateway hold more than that in memory.
 *
 * <p>{@code Content-Length} answers most of it; a chunked request has none, so the body is read here
 * up to one byte past the limit and handed on from memory. Both refuse with {@code 413}. Only the
 * webhook path is filtered: the host application's own endpoints are not this module's business.
 */
public class WebhookBodyLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final org.apache.commons.logging.Log log =
            org.apache.commons.logging.LogFactory.getLog(WebhookBodyLimitFilter.class);

    private final WebhookGatewayProperties properties;

    public WebhookBodyLimitFilter(WebhookGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var prefix = properties.getPath() == null ? "/webhooks" : properties.getPath().replaceAll("/+$", "");
        return !normalizedPath(request).startsWith(prefix + "/");
    }

    /**
     * The path the way the controller's mapping sees it, not as the raw request URI: decoded,
     * {@code ;parameters} stripped, slashes collapsed and dot segments resolved — so
     * {@code /webhooks;x=y/github}, {@code /%77ebhooks/github} or {@code //webhooks/github}, which all
     * reach the controller, cannot be written past this filter.
     */
    static String normalizedPath(HttpServletRequest request) {
        String path;
        try {
            path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (RuntimeException e) {
            path = request.getRequestURI();
        }
        if (path == null) {
            return "";
        }
        var segments = new java.util.ArrayDeque<String>();
        for (var segment : path.split("/+")) {
            var bare = segment.contains(";") ? segment.substring(0, segment.indexOf(';')) : segment;
            if (bare.isEmpty() || bare.equals(".")) {
                continue;
            }
            if (bare.equals("..")) {
                segments.pollLast();
                continue;
            }
            segments.addLast(bare);
        }
        return "/" + String.join("/", segments) + (path.endsWith("/") && !segments.isEmpty() ? "/" : "");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var max = properties.getMaxBodyBytes();
        var declared = request.getContentLengthLong();
        if (declared > max) {
            refuse(request, response, declared + " bytes", max);
            return;
        }
        var body = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 1, max + 1));
        if (body.length > max) {
            refuse(request, response, "more than " + max + " bytes", max);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, String size, long max)
            throws IOException {
        log.warn("Refused webhook " + request.getMethod() + " " + request.getRequestURI() + ": the body is " + size
                + ", over the " + max + " byte limit (workflow.webhooks.max-body-bytes)");
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(("{\"status\":413,\"title\":\"Payload Too Large\",\"detail\":\"The webhook body is"
                + " over the " + max + " byte limit.\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** The request, its body already read (and bounded) by this filter. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("the body is already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            var encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
