package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs the operator-configured (NOT user-supplied) start/stop command for the gateway.
 * Commands come from config only → no command injection from the UI.
 */
@Slf4j
@Service
public class GatewayControlService {

    public record ControlResult(boolean ok, int exitCode, String output) {}

    private final String startCommand;
    private final String stopCommand;

    public GatewayControlService(@Value("${ops.gateway.start-command}") String startCommand,
                                 @Value("${ops.gateway.stop-command}") String stopCommand) {
        this.startCommand = startCommand;
        this.stopCommand = stopCommand;
    }

    public ControlResult start() { return run(startCommand); }
    public ControlResult stop() { return run(stopCommand); }

    private ControlResult run(String command) {
        if (command == null || command.isBlank()) {
            return new ControlResult(false, -1, "command not configured");
        }
        try {
            // Split on whitespace — the command is a trusted config value (systemctl ... / a script path).
            ProcessBuilder pb = new ProcessBuilder(command.trim().split("\\s+"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ControlResult(false, -1, out + "\n(command timed out after 30s)");
            }
            int code = p.exitValue();
            return new ControlResult(code == 0, code, out.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gateway control command interrupted: {}", e.getMessage());
            return new ControlResult(false, -1, "interrupted: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Gateway control command failed: {}", e.getMessage());
            return new ControlResult(false, -1, "failed to run command: " + e.getMessage());
        }
    }
}
