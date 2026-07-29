package uz.greenwhite.gateway.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link AuthorizationProvider} that allows every request.
 * Activated only if no other {@code AuthorizationProvider} bean is registered.
 */
@Component
@ConditionalOnMissingBean(value = AuthorizationProvider.class, ignored = NoOpAuthorizationProvider.class)
public class NoOpAuthorizationProvider implements AuthorizationProvider {

    @Override
    public AuthResult authorize(AuthContext context) {
        return AuthResult.allowed();
    }
}
