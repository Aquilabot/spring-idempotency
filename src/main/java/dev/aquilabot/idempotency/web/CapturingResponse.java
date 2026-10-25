package dev.aquilabot.idempotency.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Holds the response body back until the filter has stored it. Also notes whether the handler used
 * {@code sendError}, because the container renders that body later, outside the filter.
 */
final class CapturingResponse extends ContentCachingResponseWrapper {

    private boolean errorSent;

    CapturingResponse(HttpServletResponse response) {
        super(response);
    }

    boolean errorSent() {
        return errorSent;
    }

    @Override
    public void sendError(int sc) throws IOException {
        errorSent = true;
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        errorSent = true;
        super.sendError(sc, msg);
    }
}
