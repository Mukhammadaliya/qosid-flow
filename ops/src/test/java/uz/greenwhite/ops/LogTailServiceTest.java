package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogTailServiceTest {

    @Test
    void lastLinesReturnsTail(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "l1\nl2\nl3\nl4\n");
        LogTailService svc = new LogTailService(log.toString());
        assertEquals(List.of("l3", "l4"), svc.lastLines(2));
    }

    @Test
    void lastLinesEmptyWhenFileAbsent(@TempDir Path dir) {
        LogTailService svc = new LogTailService(dir.resolve("nope.log").toString());
        assertTrue(svc.lastLines(10).isEmpty());
    }

    @Test
    void pollNewLinesStreamsAppendsAfterPrimingToEnd(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "old1\nold2\n");
        LogTailService svc = new LogTailService(log.toString());
        assertTrue(svc.pollNewLines().isEmpty()); // primes to EOF — old lines NOT re-streamed
        Files.writeString(log, "new1\nnew2\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(List.of("new1", "new2"), svc.pollNewLines());
    }

    @Test
    void pollResetsOnRotation(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "a\nb\n");
        LogTailService svc = new LogTailService(log.toString());
        svc.pollNewLines();                 // prime to EOF (pos=4)
        Files.writeString(log, "fresh\n");  // rotation: file truncated/replaced, smaller than pos
        assertEquals(List.of("fresh"), svc.pollNewLines()); // detects shrink → reads from 0
    }
}
