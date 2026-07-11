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
        AuthResult r = AuthResult.allowed(42L, "branch-9");
        assertTrue(r.isAllowed());
        assertEquals(42L, r.getTenantId());
        assertEquals("branch-9", r.getExtraTenantId());
    }

    @Test
    void deniedHasNullTenant() {
        AuthResult r = AuthResult.denied("nope");
        assertFalse(r.isAllowed());
        assertNull(r.getTenantId());
    }
}
