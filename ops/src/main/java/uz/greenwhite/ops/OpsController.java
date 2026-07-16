package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsController {

    private final GatewayStatusService statusService;
    private final GatewayControlService controlService;
    private final LogTailService logTailService;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("up", statusService.isUp());
    }

    @PostMapping("/start")
    public GatewayControlService.ControlResult start() {
        return controlService.start();
    }

    @PostMapping("/stop")
    public GatewayControlService.ControlResult stop() {
        return controlService.stop();
    }

    @GetMapping("/logs/stream")
    public SseEmitter streamLogs() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            for (String line : logTailService.lastLines(200)) {
                emitter.send(SseEmitter.event().data(line));
            }
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
            return emitter;
        }
        emitters.add(emitter);
        return emitter;
    }

    /** Push newly-appended log lines to all connected clients (~1s cadence). */
    @Scheduled(fixedDelay = 1000)
    public void pushNewLines() {
        if (emitters.isEmpty()) { logTailService.pollNewLines(); return; } // keep position current
        List<String> newLines = logTailService.pollNewLines();
        if (newLines.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                for (String line : newLines) emitter.send(SseEmitter.event().data(line));
            } catch (Exception e) {
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }
}
