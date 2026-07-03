package network.vonix.guardian.core.logfile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonWriter;
import network.vonix.guardian.core.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only, daily-rotated JSON-Lines audit log. One file per UTC day at
 * {@code <directory>/audit-YYYY-MM-DD.log.jsonl}; rotation handled lazily on
 * the next {@link #append(Action)} after the UTC date advances. See
 * SHARED-CONTRACTS.md § 6.
 *
 * <p>This class is designed to be driven from the queue worker thread; a single
 * {@link ReentrantLock} guards writer + rotation state. IO failures are logged
 * with the {@code VONIXGUARDIAN_LOGFILE} marker and the offending event is
 * dropped — {@link #append(Action)} never throws.
 */
public final class JsonLinesLogFile implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JsonLinesLogFile.class);

    private final Path directory;
    private final boolean gzipRotated;
    private final int retentionDays;
    /** v1.3.1 X6 (P3-4): opt-out toggle for the per-flush fsync (see doFlush). */
    private final boolean forceSyncOnFlush;
    private final Clock clock;
    private final Gson gson;
    private final ReentrantLock lock = new ReentrantLock();

    private LocalDate currentDate;
    private BufferedWriter writer;
    private FileChannel channel;
    private FileLock fileLock;

    public JsonLinesLogFile(Path directory, boolean gzipRotated, int retentionDays, Clock clock) {
        this(directory, gzipRotated, retentionDays, true, clock);
    }

    public JsonLinesLogFile(Path directory, boolean gzipRotated, int retentionDays,
                            boolean forceSyncOnFlush, Clock clock) {
        this.directory = directory;
        this.gzipRotated = gzipRotated;
        this.retentionDays = retentionDays;
        this.forceSyncOnFlush = forceSyncOnFlush;
        this.clock = clock;
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Action.class, new ActionAdapter())
                .serializeNulls()
                .disableHtmlEscaping()
                .create();
    }

    /** Append a single action as one JSON line. Never throws — IO errors are logged and the event dropped. */
    public void append(Action a) {
        if (a == null) {
            return;
        }
        lock.lock();
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            if (writer == null || !today.equals(currentDate)) {
                rollTo(today);
            }
            if (writer == null) {
                return; // open failed; already warned
            }
            String line = gson.toJson(a, Action.class);
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            LOG.warn(LogRotator.MARKER, "Failed to append audit event; dropping: {}", e.toString());
        } catch (RuntimeException e) {
            LOG.warn(LogRotator.MARKER, "Failed to serialize audit event; dropping: {}", e.toString());
        } finally {
            lock.unlock();
        }
    }

    /** Flush OS buffers to disk. Best-effort; failures are logged and swallowed. */
    public void flush() {
        lock.lock();
        try {
            doFlush();
        } finally {
            lock.unlock();
        }
    }

    private void doFlush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            if (forceSyncOnFlush && channel != null && channel.isOpen()) {
                channel.force(false);
            }
        } catch (IOException e) {
            LOG.warn(LogRotator.MARKER, "Failed to flush audit log: {}", e.toString());
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeCurrent();
        } finally {
            lock.unlock();
        }
    }

    // ----- internals ---------------------------------------------------------

    private void rollTo(LocalDate today) {
        closeCurrent();
        try {
            Files.createDirectories(directory);
            LogRotator.rotateIfNeeded(directory, today, gzipRotated, retentionDays);
            Path file = directory.resolve(LogRotator.fileNameFor(today));
            FileChannel ch = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            FileLock fl;
            try {
                fl = ch.tryLock();
            } catch (IOException lockEx) {
                fl = null;
            }
            // Even if tryLock fails (e.g. some filesystems), we still proceed — the
            // queue worker is the only writer in-process.
            this.channel = ch;
            this.fileLock = fl;
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            this.currentDate = today;
        } catch (IOException e) {
            LOG.warn(LogRotator.MARKER, "Failed to open audit log for {}: {}", today, e.toString());
            this.writer = null;
            this.channel = null;
            this.fileLock = null;
            this.currentDate = null;
        }
    }

    private void closeCurrent() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                LOG.warn(LogRotator.MARKER, "Failed to flush on close: {}", e.toString());
            }
            try {
                writer.close();
            } catch (IOException e) {
                LOG.warn(LogRotator.MARKER, "Failed to close writer: {}", e.toString());
            }
            writer = null;
        }
        if (fileLock != null) {
            try {
                fileLock.release();
            } catch (IOException e) {
                // ignore
            }
            fileLock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // ignore
            }
            channel = null;
        }
    }

    /**
     * Gson TypeAdapter that emits the exact field order documented in
     * SHARED-CONTRACTS.md § 6: ts, type, actor{uuid,name}, world, pos[x,y,z],
     * target, amount, source, meta.
     */
    private static final class ActionAdapter extends TypeAdapter<Action> {
        @Override
        public void write(JsonWriter out, Action a) throws IOException {
            out.beginObject();
            out.name("ts").value(a.timestamp());
            out.name("type").value(a.type() == null ? null : a.type().name());

            out.name("actor").beginObject();
            UUID uuid = a.actorUuid();
            if (uuid == null) {
                out.name("uuid").nullValue();
            } else {
                out.name("uuid").value(uuid.toString());
            }
            out.name("name").value(a.actorName());
            out.endObject();

            out.name("world").value(a.worldId());

            out.name("pos").beginArray();
            out.value(a.x());
            out.value(a.y());
            out.value(a.z());
            out.endArray();

            out.name("target").value(a.targetId());
            out.name("amount").value(a.amount());

            String src = a.sourceTag();
            if (src == null) {
                out.name("source").nullValue();
            } else {
                out.name("source").value(src);
            }

            String meta = a.targetMeta();
            if (meta == null) {
                out.name("meta").nullValue();
            } else {
                out.name("meta").value(meta);
            }
            out.endObject();
        }

        @Override
        public Action read(com.google.gson.stream.JsonReader in) {
            throw new UnsupportedOperationException("audit log is write-only");
        }
    }
}
