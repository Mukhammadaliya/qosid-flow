package uz.greenwhite.gateway.validation;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallbackUrlValidatorTest {

    private final CallbackUrlValidator open = new CallbackUrlValidator(List.of());

    @Test void publicHttpsUrlAllowed()   { assertDoesNotThrow(() -> open.validate("https://8.8.8.8/hook")); }
    @Test void loopbackRejected()        { assertThrows(RequestValidationException.class, () -> open.validate("http://127.0.0.1/x")); }
    @Test void localhostRejected()       { assertThrows(RequestValidationException.class, () -> open.validate("http://localhost:8080/x")); }
    @Test void privateRfc1918Rejected()  { assertThrows(RequestValidationException.class, () -> open.validate("http://10.0.0.5/x")); }
    @Test void private172Rejected()      { assertThrows(RequestValidationException.class, () -> open.validate("http://172.16.1.1/x")); }
    @Test void private192Rejected()      { assertThrows(RequestValidationException.class, () -> open.validate("http://192.168.1.10/x")); }
    @Test void cloudMetadataRejected()   { assertThrows(RequestValidationException.class, () -> open.validate("http://169.254.169.254/latest/meta-data")); }
    @Test void nonHttpSchemeRejected()   { assertThrows(RequestValidationException.class, () -> open.validate("ftp://example.com/x")); }
    @Test void garbageRejected()         { assertThrows(RequestValidationException.class, () -> open.validate("not a url")); }
    @Test void ipv6UniqueLocalRejected() { assertThrows(RequestValidationException.class, () -> open.validate("http://[fc00::1]/x")); }
    @Test void cgnatRejected()           { assertThrows(RequestValidationException.class, () -> open.validate("http://100.64.0.1/x")); }
    @Test void broadcastRejected()       { assertThrows(RequestValidationException.class, () -> open.validate("http://255.255.255.255/x")); }

    @Test
    void allowlistRestrictsToListedHosts() {
        CallbackUrlValidator strict = new CallbackUrlValidator(List.of("hooks.example.com"));
        assertDoesNotThrow(() -> strict.validate("https://hooks.example.com/cb"));
        assertThrows(RequestValidationException.class, () -> strict.validate("https://8.8.8.8/cb"));
    }
}
