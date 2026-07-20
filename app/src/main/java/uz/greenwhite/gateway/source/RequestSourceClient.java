package uz.greenwhite.gateway.source;

import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.util.List;

/**
 * Interface for pulling requests from any source system.
 * Implementations may connect to Oracle-backed REST (e.g. Biruni), PostgreSQL,
 * REST APIs, file systems, or any other source. {@code SourceClient} is the
 * reference implementation; see {@code ai-context/SOURCE-INTEGRATION-CONTRACT.md}.
 */
public interface RequestSourceClient {

    /**
     * Pull pending requests from the data source.
     * Each call should return a batch of unprocessed requests.
     *
     * @return list of request messages ready for processing
     */
    List<RequestMessage> pullRequests();
}