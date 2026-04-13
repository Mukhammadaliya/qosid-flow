package uz.greenwhite.gateway.auth;

/**
 * Pluggable authorization hook. Default implementation is
 * {@link NoOpAuthorizationProvider}. Replace by registering another bean
 * that implements this interface — Spring will pick it up and the NoOp will
 * step aside (see {@code @ConditionalOnMissingBean} on the default).
 */
public interface AuthorizationProvider {

    AuthResult authorize(AuthContext context);
}
