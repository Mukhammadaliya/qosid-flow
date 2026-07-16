package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class GatewayStatusService {

    private final RestClient client;
    private final String healthUrl;

    public GatewayStatusService(RestClient.Builder builder,
                                @Value("${ops.gateway.health-url}") String healthUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(2000);
        this.client = builder.requestFactory(f).build();
        this.healthUrl = healthUrl;
    }

    /** True iff the gateway health endpoint answers 2xx within the timeout. Never throws. */
    public boolean isUp() {
        try {
            return client.get().uri(healthUrl).retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Gateway health check failed: {}", e.getMessage());
            return false;
        }
    }
}
