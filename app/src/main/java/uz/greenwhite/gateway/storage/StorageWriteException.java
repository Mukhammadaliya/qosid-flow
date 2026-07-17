package uz.greenwhite.gateway.storage;

/** Thrown when a RocksDB write fails — callers must NOT report the response as stored. */
public class StorageWriteException extends RuntimeException {
    public StorageWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
