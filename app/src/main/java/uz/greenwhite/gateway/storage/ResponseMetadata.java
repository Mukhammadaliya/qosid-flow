package uz.greenwhite.gateway.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata describing a response body stored in RocksDB.
 * Persisted alongside the body under the {@code response_meta:} key prefix.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseMetadata {

    /** HTTP status returned by the upstream API. */
    private int httpStatus;

    /** Original response Content-Type. */
    private String contentType;

    /** Size of the stored body in bytes. */
    private long contentLength;

    /** Epoch milliseconds when this response was written to RocksDB. */
    private long savedAt;
}
