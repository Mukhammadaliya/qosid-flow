package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import uz.greenwhite.qosid.settings.AdminAccount;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    @Mock AdminAccountRepository repo;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminAuthService service() {
        return new AdminAuthService(repo, encoder, new LoginRateLimiter());
    }

    @Test
    void authenticateSucceedsWithCorrectPassword() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertTrue(s.authenticate("admin", "admin"));
        verify(repo).updateLastLogin("admin");
    }

    @Test
    void authenticateFailsWithWrongPassword() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertFalse(s.authenticate("admin", "wrong"));
    }

    @Test
    void rateLimitBlocksAfterFiveFailures() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        for (int i = 0; i < 5; i++) s.authenticate("admin", "wrong");
        assertThrows(LoginRateLimiter.TooManyAttemptsException.class, () -> s.authenticate("admin", "admin"));
    }

    @Test
    void changePasswordRequiresCorrectOld() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.changePassword("admin", "wrong", "newpass1"));
        s.changePassword("admin", "admin", "newpass1");
        verify(repo).updatePassword(eq("admin"), anyString());
    }
}
