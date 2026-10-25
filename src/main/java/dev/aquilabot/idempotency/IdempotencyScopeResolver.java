package dev.aquilabot.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * Decides which client a key belongs to. Keys are unique per scope, so two clients that pick the
 * same key do not see each other's responses.
 *
 * <p>The default uses the authenticated principal's name, or the empty string for anonymous
 * requests. Register your own bean to scope by API key, tenant or similar.
 */
@FunctionalInterface
public interface IdempotencyScopeResolver {

    String resolve(HttpServletRequest request);

    static IdempotencyScopeResolver principalName() {
        return request -> {
            Principal principal = request.getUserPrincipal();
            return principal == null ? "" : principal.getName();
        };
    }
}
