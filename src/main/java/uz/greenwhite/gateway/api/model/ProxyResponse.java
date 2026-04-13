package uz.greenwhite.gateway.api.model;

import lombok.Builder;
import lombok.Data;

/**
 * Body returned by {@code POST/GET/PUT/DELETE/PATCH /qosid/queue/**}
 * confirming the request was accepted and queued for processing.
 */
@Data
@Builder
public class ProxyResponse {

    /** Composite request ID, e.g. {@code "api:0:1234567890"}, used for status lookups. */
    private String requestId;

    /** Always {@code "ACCEPTED"} for the synchronous handshake. */
    private String status;

    /** Pre-built URL the client can poll for status. */
    private String statusUrl;
}
