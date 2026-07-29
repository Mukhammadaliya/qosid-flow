package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tails the gateway log file: last-N snapshot + incremental new-line polling with rotation reset. */
@Slf4j
@Service
public class LogTailService {

    private static final int MAX_TAIL_BYTES = 256 * 1024;
    private static final int ANCHOR_BYTES = 64;

    private final Path logFile;
    private long position = -1;      // -1 = not yet primed
    private byte[] anchor = new byte[0]; // bytes immediately preceding position, for rotation detection

    public LogTailService(@Value("${ops.log-file}") String logFile) {
        this.logFile = Path.of(logFile);
    }

    /** Up to the last n lines; empty if the file is absent/unreadable. */
    public synchronized List<String> lastLines(int n) {
        if (!Files.isReadable(logFile)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - MAX_TAIL_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String[] all = new String(buf, StandardCharsets.UTF_8).split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (String s : all) if (!s.isEmpty()) lines.add(s);
            int start = Math.max(0, lines.size() - n);
            return new ArrayList<>(lines.subList(start, lines.size()));
        } catch (IOException e) {
            log.debug("lastLines read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** New lines appended since the last poll. First call primes to EOF (returns empty). Resets on rotation. */
    public synchronized List<String> pollNewLines() {
        if (!Files.isReadable(logFile)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = raf.length();
            if (position < 0) {
                // first poll: prime to EOF so only NEW lines stream (old lines not re-streamed)
                position = len;
                anchor = bytesEndingAt(raf, position);
                return List.of();
            }
            if (position > len || !Arrays.equals(anchor, bytesEndingAt(raf, position))) {
                // rotation: file shrank OR the content preceding our position changed → read from 0
                position = 0;
                anchor = new byte[0];
            }
            if (position == len) return List.of();
            raf.seek(position);
            byte[] buf = new byte[(int) (len - position)];
            raf.readFully(buf);
            // trailing partial line (no terminating newline yet) is NOT emitted until its newline arrives:
            // advance position only to just past the last newline (byte scan — safe for multibyte UTF-8)
            int lastNl = -1;
            for (int i = buf.length - 1; i >= 0; i--) {
                if (buf[i] == '\n') { lastNl = i; break; }
            }
            if (lastNl < 0) return List.of(); // whole chunk is one partial line; position unchanged
            position = position + lastNl + 1;
            anchor = bytesEndingAt(raf, position);
            String[] parts = new String(buf, 0, lastNl + 1, StandardCharsets.UTF_8).split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < parts.length - 1; i++) lines.add(parts[i]); // last element is "" after final \n
            return lines;
        } catch (IOException e) {
            log.debug("pollNewLines read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Up to ANCHOR_BYTES bytes ending at pos (exclusive) — fingerprint used to detect rotation. */
    private static byte[] bytesEndingAt(RandomAccessFile raf, long pos) throws IOException {
        int n = (int) Math.min(ANCHOR_BYTES, pos);
        byte[] b = new byte[n];
        raf.seek(pos - n);
        raf.readFully(b);
        return b;
    }
}
