package uz.greenwhite.gateway.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthResultTest {

    @Test
    void allowedNoTenantHasNullTenant() {
        AuthResult r = AuthResult.allowed();
        assertTrue(r.isAllowed());
        assertNull(r.getTenantId());
        assertNull(r.getExtraTenantId());
    }

    @Test
    void allowedWithTenantCarriesIt() {
        AuthResult r = AuthResult.allowed(42L, 9L);
        assertTrue(r.isAllowed());
        assertEquals(42L, r.getTenantId());
        assertEquals(9L, r.getExtraTenantId());
    }

    @Test
    void deniedHasNullTenant() {
        AuthResult r = AuthResult.denied("nope");
        assertFalse(r.isAllowed());
        assertNull(r.getTenantId());
    }
}
