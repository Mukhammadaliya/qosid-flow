package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.qosid.settings.AdminAccountRepository;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix I4: the reset marker must be recoverable — leaving ADMIN_RESET unset on a boot clears
 * the marker, so a LATER ADMIN_RESET=true (after the env var was removed and re-added) applies
 * again, while a directive that is unchanged since the last apply (same boot / restart with the
 * same env var still set) remains a no-op.
 */
@ExtendWith(MockitoExtension.class)
class AdminAccountInitializerTest {

    private static final String MARKER_KEY = "admin.reset-marker";

    @Mock AdminAccountRepository repository;
    @Mock Environment environment;
    @Mock SettingsService settingsService;

    private final Map<String, String> settingsStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(repository.count()).thenReturn(1);
        lenient().when(settingsService.get(eq(MARKER_KEY), anyString()))
                .thenAnswer(inv -> settingsStore.getOrDefault(MARKER_KEY, inv.getArgument(1)));
        lenient().doAnswer(inv -> {
            settingsStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(settingsService).set(eq(MARKER_KEY), anyString(), anyBoolean(), anyString());
    }

    private AdminAccountInitializer initializer() {
        return new AdminAccountInitializer(repository, environment, settingsService);
    }

    @Test
    void noDirectiveClearsStaleMarker() {
        settingsStore.put(MARKER_KEY, "DEFAULT");
        when(environment.getProperty("ADMIN_PASSWORD_RESET")).thenReturn(null);
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("false");

        initializer().run(null);

        assertEquals("", settingsStore.get(MARKER_KEY));
        verify(repository, never()).updatePassword(any(), any());
    }

    @Test
    void firstResetAppliesAndSetsMarker() {
        when(environment.getProperty("ADMIN_PASSWORD_RESET")).thenReturn(null);
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("true");

        initializer().run(null);

        verify(repository).updatePassword(eq("admin"), anyString());
        assertEquals("DEFAULT", settingsStore.get(MARKER_KEY));
    }

    @Test
    void sameDirectiveTwiceInARowIsNoOp() {
        settingsStore.put(MARKER_KEY, "DEFAULT");
        when(environment.getProperty("ADMIN_PASSWORD_RESET")).thenReturn(null);
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("true");

        initializer().run(null);

        verify(repository, never()).updatePassword(any(), any());
    }

    @Test
    void resetIsRecoverableAfterRemovingAndReAddingEnvVar() {
        // Boot 1: ADMIN_RESET=true applies.
        when(environment.getProperty("ADMIN_PASSWORD_RESET")).thenReturn(null);
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("true");
        initializer().run(null);
        verify(repository, times(1)).updatePassword(eq("admin"), anyString());
        assertEquals("DEFAULT", settingsStore.get(MARKER_KEY));

        // Boot 2: env var removed -> marker cleared, no further reset.
        reset(environment);
        when(environment.getProperty("ADMIN_PASSWORD_RESET")).thenReturn(null);
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("false");
        initializer().run(null);
        assertEquals("", settingsStore.get(MARKER_KEY));
        verify(repository, times(1)).updatePassword(eq("admin"), anyString());

        // Boot 3: env var re-added -> reset applies again (this is the behavior that was
        // broken before Fix I4 — the marker would have permanently blocked this).
        when(environment.getProperty("ADMIN_RESET", "false")).thenReturn("true");
        initializer().run(null);
        verify(repository, times(2)).updatePassword(eq("admin"), anyString());
        assertEquals("DEFAULT", settingsStore.get(MARKER_KEY));
    }
}
