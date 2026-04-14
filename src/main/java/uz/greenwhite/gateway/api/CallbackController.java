package uz.greenwhite.gateway.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.api.model.CallbackPayload;

/**
 * Receiver endpoint for inbound callback notifications. Currently logs the
 * payload and returns 200 — domain handling is reserved for later iterations.
 */
@Slf4j
@RestController
@RequestMapping("${gateway.proxy.context-path}/api/v1/callback")
public class CallbackController {

    @PostMapping
    public ResponseEntity<Void> handleCallback(@RequestBody CallbackPayload payload) {
        log.info("Received callback: requestId={}, status={}",
                payload.getRequestId(), payload.getStatus());
        return ResponseEntity.ok().build();
    }
}
