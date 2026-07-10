package uz.greenwhite.gateway.callback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.api.model.CallbackPayload;
import uz.greenwhite.gateway.validation.CallbackUrlValidator;

/**
 * Synchronous HTTP POST sender for completion callbacks.
 *
 * <p>Wrapped in {@link Retryable} so transient failures (network blips, 5xx, timeouts)
 * are retried up to 3 times with a 1s back-off. After exhausting retries the
 * {@link Recover} method records the failure — we deliberately do not propagate
 * the exception further so the calling Kafka consumer can still acknowledge.</p>
 *
 * <p>Uses the synchronous {@link RestClient} rather than {@code WebClient} so the
 * blocking semantics required by spring-retry's AOP interception work cleanly.</p>
 *
 * <p>SSRF note: the callback URL was validated at accept time by
 * {@link CallbackUrlValidator}, and is re-validated here immediately before
 * sending to narrow the DNS-rebinding window between accept and delivery.
 * Full DNS pinning (resolving once and forcing the HTTP client to connect to
 * that exact address via a custom DnsResolver) is deliberately out of scope —
 * the send-time re-check plus the accept-time check is the accepted trade-off.</p>
 */
@Slf4j
@Service
public class CallbackSender {

    private final RestClient restClient;
    private final CallbackUrlValidator callbackUrlValidator;

    public CallbackSender(CallbackUrlValidator callbackUrlValidator) {
        this.restClient = RestClient.builder().build();
        this.callbackUrlValidator = callbackUrlValidator;
    }

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void sendCallback(String callbackUrl, CallbackPayload payload) {
        callbackUrlValidator.validate(callbackUrl);
        log.info("Sending callback to {}: requestId={}", callbackUrl, payload.getRequestId());

        HttpStatusCode status = restClient.post()
                .uri(callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();

        if (!status.is2xxSuccessful()) {
            // Force a retry: spring-retry only triggers on thrown exceptions.
            throw new IllegalStateException(
                    "Callback returned non-2xx status " + status.value() + " for " + callbackUrl);
        }

        log.debug("Callback delivered to {}: requestId={}, status={}",
                callbackUrl, payload.getRequestId(), status.value());
    }

    @Recover
    public void recoverCallback(Exception e, String callbackUrl, CallbackPayload payload) {
        log.error("Callback failed after 3 attempts: url={}, requestId={}, error={}",
                callbackUrl, payload.getRequestId(), e.getMessage());
    }
}
