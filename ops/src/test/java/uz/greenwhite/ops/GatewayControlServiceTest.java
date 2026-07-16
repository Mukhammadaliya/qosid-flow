package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayControlServiceTest {

    private final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
    private String echo(String s) { return windows ? "cmd /c echo " + s : "echo " + s; }

    @Test
    void startRunsConfiguredCommandAndCapturesOutput() {
        GatewayControlService svc = new GatewayControlService(echo("started-ok"), echo("stopped-ok"));
        GatewayControlService.ControlResult r = svc.start();
        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("started-ok"));
    }

    @Test
    void failureToLaunchIsStructuredNotThrown() {
        GatewayControlService svc = new GatewayControlService("no-such-binary-xyz", "no-such-binary-xyz");
        GatewayControlService.ControlResult r = svc.stop();
        assertFalse(r.ok());
        assertEquals(-1, r.exitCode());
        assertNotNull(r.output());
    }
}
