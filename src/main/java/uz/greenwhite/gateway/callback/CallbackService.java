package uz.greenwhite.gateway.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.api.model.CallbackPayload;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;

import java.nio.charset.StandardCharsets;

/**
 * Builds a {@link CallbackPayload} from a finished {@link ResponseMessage} and
 * dispatches it via {@link CallbackSender} when the originating request supplied
 * a callback URL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final CallbackSender callbackSender;
    private final ObjectMapper objectMapper;

    @Value("${gateway.response.threshold-size:1024}")
    private int thresholdSize;

    /**
     * No-op if {@link ResponseMessage#getCallbackUrl()} is absent. Otherwise builds
     * the payload (inlining small JSON/text bodies, otherwise emitting a
     * {@code resultUrl}) and hands it to the sender.
     */
    public void sendCallbackIfNeeded(ResponseMessage response) {
        String callbackUrl = response.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return;
        }

        CallbackPayload.CallbackPayloadBuilder builder = CallbackPayload.builder()
                .requestId(response.getCompositeId())
                .status(response.isSuccess() ? "COMPLETED" : "FAILED")
                .httpStatus(response.getHttpStatus())
                .contentType(response.getContentType());

        if (!response.isSuccess()) {
            builder.errorMessage(response.getErrorMessage());
        }

        byte[] body = response.getBody();
        if (body != null) {
            if (body.length < thresholdSize) {
                // Inline small bodies: try JSON first, fall back to UTF-8 string.
                try {
                    builder.result(objectMapper.readTree(body));
                } catch (Exception e) {
                    builder.result(new String(body, StandardCharsets.UTF_8));
                }
            } else {
                builder.resultUrl("/qosid/api/v1/requests/" + response.getCompositeId() + "/result");
            }
        }

        callbackSender.sendCallback(callbackUrl, builder.build());
    }
}
