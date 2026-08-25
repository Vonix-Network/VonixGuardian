package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.ByteBuffer;

/**
 * Small framed append-only journal for actions that exhausted sink retries.
 * ADD records make rows recoverable after restart; SINK_SUCCEEDED records mark
 * that the recovery sink flush already completed; ACK records retire rows only
 * after journal retirement succeeds. Inventory replacement pairs use
 * {@link #markSinkSucceededGroup} / {@link #acknowledgeGroup} so a crash
 * cannot retire one member without the other.
 */
class QuarantineStore {

    /** Package-private deterministic seam used only by durability regression tests. */
    interface AppendFaultInjector {
        void write(FileChannel channel, byte[] frame) throws IOException;

        default void beforeRollback(Path path, long priorBytes) throws IOException {
            // no-op
        }
    }

    static final int MAX_ENTRIES = 100_000;
    static final long MAX_JOURNAL_BYTES = 256L * 1024L * 1024L;
    /** Pre-v6 journal frames (no pairId). */
    private static final int MAGIC_V1 = 0x56475131;
    /** Current journal frames: trailing pairId and inventory slot after NBT bytes. */
    private static final int MAGIC_V2 = 0x56475132;
    /** v7 journal frames add nullable inventory-slot identity. */
    private static final int MAGIC_V3 = 0x56475133;
    private static final int MAGIC = MAGIC_V3;
    private static final byte ADD = 1;
    private static final byte ACK = 2;
    /** Durable marker: recovery sink flush succeeded; only journal ACK remains. */
    private static final byte SINK_SUCCEEDED = 3;
    /**
     * One frame marking sink success for every listed sequence. Partial
     * application is impossible: load buffers the full sequence list before
     * mutating {@link #active}.
     */
    private static final byte GROUP_SINK_SUCCEEDED = 4;
    /** One frame retiring every listed sequence. */
    private static final byte GROUP_ACK = 5;
    /** One framed admission for every member of a replacement group. */
    private static final byte GROUP_ADD = 6;
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;

    record Entry(long sequence, Action action, boolean sinkSucceeded) {}

    private final Path path;
    private final int maxEntries;
    private final long maxJournalBytes;
    private final LinkedHashMap<Long, Entry> active = new LinkedHashMap<>();
    private final AppendFaultInjector appendFaultInjector;
    private long nextSequence = 1L;
    /** Non-null after an append could not be rolled back to its prior length. */
    private IOException poisoned;

    QuarantineStore(Path path) throws IOException {
        this(path, MAX_ENTRIES, MAX_JOURNAL_BYTES, null);
    }

    QuarantineStore(Path path, int maxEntries, long maxJournalBytes) throws IOException {
        this(path, maxEntries, maxJournalBytes, null);
    }

    QuarantineStore(Path path, int maxEntries, long maxJournalBytes,
                    AppendFaultInjector appendFaultInjector) throws IOException {
        if (maxEntries <= 0 || maxJournalBytes <= 0L) {
            throw new IllegalArgumentException("quarantine limits must be > 0");
        }
        this.path = path;
        this.maxEntries = maxEntries;
        this.maxJournalBytes = maxJournalBytes;
        this.appendFaultInjector = appendFaultInjector;
        Path parent = path.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        forceDirectory(parent);
        load();
    }

    synchronized List<Entry> entries() {
        return new ArrayList<>(active.values());
    }

    synchronized long append(Action action) throws IOException {
        ensureHealthy();
        // InterruptedException from queue backoff may have restored the flag.
        // Clear it for the complete append + FileChannel.force durability window;
        // restore it only after the journal operation has returned.
        boolean restoreInterrupt = Thread.interrupted();
        try {
            if (active.size() >= maxEntries) return -1L;

            long sequence = nextSequence;
            if (sequence <= 0L || sequence == Long.MAX_VALUE) {
                return -1L;
            }
            // Encode the full ADD frame first so the hard byte cap is preflighted
            // against the exact next record, not just the current journal size.
            byte[] frame = encodeAddFrame(sequence, action);
            if (frame.length > maxJournalBytes) {
                return -1L;
            }

            long currentBytes = Files.exists(path) ? Files.size(path) : 0L;
            if (currentBytes + frame.length > maxJournalBytes) {
                compact();
                currentBytes = Files.exists(path) ? Files.size(path) : 0L;
                if (currentBytes + frame.length > maxJournalBytes) {
                    return -1L;
                }
            }

            nextSequence = sequence + 1L;
            appendBytes(frame);
            active.put(sequence, new Entry(sequence, action, false));
            return sequence;
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /**
     * Append every action in one framed journal record so a replacement pair cannot
     * retain only one half after a crash during admission. Returns an empty list
     * when the group would exceed retention limits; the caller must drop all
     * members together.
     */
    synchronized List<Long> appendGroup(List<Action> actions) throws IOException {
        ensureHealthy();
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        if (actions.size() == 1) {
            long sequence = append(actions.get(0));
            return sequence < 0L ? List.of() : List.of(sequence);
        }
        boolean restoreInterrupt = Thread.interrupted();
        try {
            if (active.size() + actions.size() > maxEntries) {
                return List.of();
            }
            long sequence = nextSequence;
            if (sequence <= 0L || sequence > Long.MAX_VALUE - actions.size()) {
                return List.of();
            }
            List<Long> sequences = new ArrayList<>(actions.size());
            for (int i = 0; i < actions.size(); i++) {
                sequences.add(sequence + i);
            }
            byte[] all = encodeGroupAddFrame(sequence, actions);
            if (all.length > maxJournalBytes) {
                return List.of();
            }
            long currentBytes = Files.exists(path) ? Files.size(path) : 0L;
            if (currentBytes + all.length > maxJournalBytes) {
                compact();
                currentBytes = Files.exists(path) ? Files.size(path) : 0L;
                if (currentBytes + all.length > maxJournalBytes) {
                    return List.of();
                }
            }
            appendBytes(all);
            nextSequence = sequence + actions.size();
            for (int i = 0; i < actions.size(); i++) {
                active.put(sequences.get(i), new Entry(sequences.get(i), actions.get(i), false));
            }
            return sequences;
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /**
     * Persist that the recovery sink flush for {@code sequence} already succeeded.
     * Loaded across restart so recovery retries only journal retirement (ACK),
     * never a second sink.flush. No-op when the row is absent or already marked.
     */
    synchronized void markSinkSucceeded(long sequence) throws IOException {
        ensureHealthy();
        Entry existing = active.get(sequence);
        if (existing == null || existing.sinkSucceeded()) {
            return;
        }
        markSinkSucceededSingle(sequence, existing);
    }

    /**
     * Persist sink-success for every listed sequence in one journal frame.
     * Explicit groups are fail-closed: malformed, missing, duplicate, or mixed
     * state members never produce a downgraded singleton marker.
     */
    synchronized void markSinkSucceededGroup(List<Long> sequences) throws IOException {
        ensureHealthy();
        validateGroup(sequences, false, "sink-success");
        boolean restoreInterrupt = Thread.interrupted();
        try {
            byte[] frame = encodeGroupOpFrame(GROUP_SINK_SUCCEEDED, sequences);
            reserveForFrame(frame, "quarantine journal full; cannot durable-mark sink success");
            appendBytes(frame);
            for (Long sequence : sequences) {
                Entry existing = active.get(sequence);
                active.put(sequence, new Entry(sequence, existing.action(), true));
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    synchronized void acknowledge(long sequence) throws IOException {
        ensureHealthy();
        if (!active.containsKey(sequence)) {
            return;
        }
        acknowledgeSingle(sequence);
    }

    /**
     * Retire every listed sequence in one journal frame. Explicit groups require
     * every member to have a durable sink-success marker before ACK.
     */
    synchronized void acknowledgeGroup(List<Long> sequences) throws IOException {
        ensureHealthy();
        validateGroup(sequences, true, "ACK");
        boolean restoreInterrupt = Thread.interrupted();
        try {
            byte[] frame = encodeGroupOpFrame(GROUP_ACK, sequences);
            if (frame.length > maxJournalBytes) {
                throw new IOException("quarantine ACK frame exceeds journal hard byte cap");
            }
            reserveForFrame(frame, "quarantine journal full; cannot append ACK");
            appendBytes(frame);
            for (Long sequence : sequences) {
                active.remove(sequence);
            }
            if (Files.exists(path) && Files.size(path) >= maxJournalBytes / 2L) compact();
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private void markSinkSucceededSingle(long sequence, Entry existing) throws IOException {
        byte[] frame = encodeOpFrame(SINK_SUCCEEDED, sequence);
        reserveForFrame(frame, "quarantine journal full; cannot durable-mark sink success");
        appendBytes(frame);
        active.put(sequence, new Entry(sequence, existing.action(), true));
    }

    private void acknowledgeSingle(long sequence) throws IOException {
        byte[] frame = encodeOpFrame(ACK, sequence);
        if (frame.length > maxJournalBytes) {
            throw new IOException("quarantine ACK frame exceeds journal hard byte cap");
        }
        reserveForFrame(frame, "quarantine journal full; cannot append ACK");
        appendBytes(frame);
        active.remove(sequence);
        if (Files.exists(path) && Files.size(path) >= maxJournalBytes / 2L) compact();
    }

    private void validateGroup(List<Long> sequences, boolean requireSinkSucceeded, String operation) {
        if (sequences == null || sequences.size() < 2) {
            throw new IllegalArgumentException("quarantine " + operation + " group requires at least two members");
        }
        java.util.HashSet<Long> unique = new java.util.HashSet<>();
        Boolean expectedState = null;
        for (Long sequence : sequences) {
            if (sequence == null || sequence <= 0L || sequence == Long.MAX_VALUE || !unique.add(sequence)) {
                throw new IllegalArgumentException("invalid quarantine " + operation + " group member");
            }
            Entry existing = active.get(sequence);
            if (existing == null) {
                throw new IllegalArgumentException("missing quarantine " + operation + " group member");
            }
            if (expectedState == null) {
                expectedState = existing.sinkSucceeded();
            } else if (expectedState != existing.sinkSucceeded()) {
                throw new IllegalArgumentException("mixed quarantine " + operation + " group state");
            }
            if (requireSinkSucceeded != existing.sinkSucceeded()) {
                throw new IllegalArgumentException("invalid prior state for quarantine " + operation + " group");
            }
        }
    }

    private void reserveForFrame(byte[] frame, String fullMessage) throws IOException {
        long currentBytes = Files.exists(path) ? Files.size(path) : 0L;
        if (currentBytes + frame.length > maxJournalBytes) {
            compact();
            currentBytes = Files.exists(path) ? Files.size(path) : 0L;
            if (currentBytes + frame.length > maxJournalBytes) {
                throw new IOException(fullMessage);
            }
        }
    }

    private void load() throws IOException {
        if (!Files.exists(path)) return;
        byte[] bytes = Files.readAllBytes(path);
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        long consumed = 0L;
        try (DataInputStream in = new DataInputStream(raw)) {
            while (raw.available() > 0) {
                long before = raw.available();
                int magic = in.readInt();
                if (magic != MAGIC_V1 && magic != MAGIC_V2 && magic != MAGIC_V3) break;
                byte operation = in.readByte();
                long sequence = in.readLong();
                if (operation == ADD) {
                    Action action = readAction(in, magic == MAGIC_V2 || magic == MAGIC_V3,
                            magic == MAGIC_V3);
                    if (sequence <= 0L || sequence == Long.MAX_VALUE || active.containsKey(sequence)) {
                        throw new IOException("invalid, exhausted, or duplicate quarantine sequence");
                    }
                    active.put(sequence, new Entry(sequence, action, false));
                    if (active.size() > maxEntries) {
                        throw new IOException("quarantine entry cap exceeded");
                    }
                    nextSequence = Math.max(nextSequence, sequence + 1L);
                } else if (operation == SINK_SUCCEEDED) {
                    Entry existing = active.get(sequence);
                    if (existing != null) {
                        active.put(sequence, new Entry(sequence, existing.action(), true));
                    }
                } else if (operation == ACK) {
                    active.remove(sequence);
                } else if (operation == GROUP_ADD) {
                    List<Action> actions = readGroupActions(in, sequence);
                    validateLoadedGroupAdd(sequence, actions.size());
                    if (active.size() + actions.size() > maxEntries) {
                        throw new IOException("quarantine entry cap exceeded");
                    }
                    for (int i = 0; i < actions.size(); i++) {
                        long memberSequence = sequence + i;
                        active.put(memberSequence, new Entry(memberSequence, actions.get(i), false));
                    }
                    nextSequence = Math.max(nextSequence, sequence + actions.size());
                } else if (operation == GROUP_SINK_SUCCEEDED) {
                    applyLoadedGroup(readGroupSequences(in, sequence), true);
                } else if (operation == GROUP_ACK) {
                    applyLoadedGroup(readGroupSequences(in, sequence), false);
                } else {
                    break;
                }
                consumed += before - raw.available();
            }
        } catch (EOFException ignored) {
            // A process interruption can leave only a partial final frame.
        }
        if (consumed < bytes.length) {
            Files.write(path, Arrays.copyOf(bytes, (int) consumed),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    private void validateLoadedGroupAdd(long firstSequence, int count) throws IOException {
        if (firstSequence <= 0L || firstSequence == Long.MAX_VALUE || count < 2
                || firstSequence > Long.MAX_VALUE - (count - 1L)) {
            throw new IOException("invalid or exhausted quarantine group sequence range");
        }
        for (int i = 0; i < count; i++) {
            long memberSequence = firstSequence + i;
            if (active.containsKey(memberSequence)) {
                throw new IOException("duplicate or overlapping quarantine group sequence");
            }
        }
    }

    private void compact() throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        long rewritten = 0L;
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {
            for (Entry entry : active.values()) {
                byte[] add = encodeAddFrame(entry.sequence(), entry.action());
                rewritten += add.length;
                if (rewritten > maxJournalBytes) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best-effort */ }
                    throw new IOException("quarantine compact rewrite exceeds journal hard byte cap");
                }
                out.write(add);
                if (entry.sinkSucceeded()) {
                    byte[] marker = encodeOpFrame(SINK_SUCCEEDED, entry.sequence());
                    rewritten += marker.length;
                    if (rewritten > maxJournalBytes) {
                        try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best-effort */ }
                        throw new IOException("quarantine compact rewrite exceeds journal hard byte cap");
                    }
                    out.write(marker);
                }
            }
            out.flush();
        }
        // Compact replaces the durable journal. Force the temp image before the
        // rename so a crash cannot leave only a half-written replacement.
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        // Directory entry for the replacement must also reach stable storage;
        // file force alone does not durable-publish the rename on Linux.
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            forceDirectory(parent);
        }
    }

    private void appendBytes(byte[] frame) throws IOException {
        ensureHealthy();
        Path parent = path.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        forceDirectory(parent);
        long priorBytes = Files.exists(path) ? Files.size(path) : 0L;
        try {
            // Write and force the complete frame through one channel. If any
            // write/force/publish step fails, the catch below restores the exact
            // prior length before the caller can append another frame.
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                if (appendFaultInjector == null) {
                    ByteBuffer buffer = ByteBuffer.wrap(frame);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                } else {
                    appendFaultInjector.write(channel, frame);
                }
                channel.force(true);
            }
            // Publish the newly-created journal entry and append metadata durably.
            forceDirectory(parent);
        } catch (IOException failure) {
            IOException rollbackFailure = null;
            try {
                if (appendFaultInjector != null) {
                    appendFaultInjector.beforeRollback(path, priorBytes);
                }
                if (Files.exists(path)) {
                    try (FileChannel rollback = FileChannel.open(path, StandardOpenOption.WRITE)) {
                        rollback.truncate(priorBytes);
                        rollback.force(true);
                    }
                    forceDirectory(parent);
                }
            } catch (IOException e) {
                rollbackFailure = e;
            }
            if (rollbackFailure != null) {
                IOException terminal = new IOException(
                        "quarantine journal poisoned: failed append rollback", failure);
                terminal.addSuppressed(rollbackFailure);
                poisoned = terminal;
            }
            throw failure;
        }
    }

    private void ensureHealthy() throws IOException {
        if (poisoned != null) {
            throw new IOException("quarantine journal is disabled after an unrollbackable append failure", poisoned);
        }
    }

    /**
     * Force parent directory metadata (Linux). Fail closed: callers must not
     * claim a durable append/compaction if directory force fails.
     * Package-private seam for deterministic unit coverage; not a power-loss claim.
     */
    static void forceDirectory(Path directory) throws IOException {
        if (directory == null) {
            throw new IOException("quarantine parent directory missing");
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            throw new IOException("quarantine directory force failed: " + directory, e);
        }
    }

    private static byte[] encodeAddFrame(long sequence, Action action) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC);
            out.writeByte(ADD);
            out.writeLong(sequence);
            writeAction(out, action);
            out.flush();
        }
        return bos.toByteArray();
    }

    private static byte[] encodeOpFrame(byte operation, long sequence) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC);
            out.writeByte(operation);
            out.writeLong(sequence);
            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * Group ADD frames carry their first sequence in the common header and the
     * count before all action payloads. The loader does not publish any member
     * until every payload has been read, so a torn final frame cannot expose a
     * recoverable singleton.
     */
    private static byte[] encodeGroupAddFrame(long firstSequence, List<Action> actions) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC);
            out.writeByte(GROUP_ADD);
            out.writeLong(firstSequence);
            out.writeInt(actions.size());
            for (Action action : actions) {
                writeAction(out, action);
            }
            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * Group frames store {@code count} in the 8-byte sequence slot of the
     * common header so a truncated tail cannot apply a prefix of the group.
     * {@code sequences} must contain at least two members.
     */
    private static byte[] encodeGroupOpFrame(byte operation, List<Long> sequences) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16 + sequences.size() * 8);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC);
            out.writeByte(operation);
            out.writeLong(sequences.size());
            for (Long sequence : sequences) {
                out.writeLong(sequence);
            }
            out.flush();
        }
        return bos.toByteArray();
    }

    /**
     * {@code firstWord} is the 8-byte field already consumed as {@code sequence}
     * by {@link #load()}. For group ops that field is the member count.
     */
    private static List<Long> readGroupSequences(DataInputStream in, long firstWord) throws IOException {
        if (firstWord < 2L || firstWord > MAX_ENTRIES) {
            throw new EOFException("invalid quarantine group size");
        }
        int count = (int) firstWord;
        List<Long> sequences = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sequences.add(in.readLong());
        }
        return sequences;
    }

    private static List<Action> readGroupActions(DataInputStream in, long firstSequence) throws IOException {
        if (firstSequence < 1L) {
            throw new EOFException("invalid quarantine group first sequence");
        }
        int count = in.readInt();
        if (count < 2 || count > MAX_ENTRIES || firstSequence > Long.MAX_VALUE - count) {
            throw new EOFException("invalid quarantine group add size");
        }
        List<Action> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            actions.add(readAction(in, true, true));
        }
        return actions;
    }

    private void applyLoadedGroup(List<Long> sequences, boolean sinkSucceeded) {
        // A group marker is all-or-none at replay time too. If a legacy or
        // corrupted journal lacks any member, leave surviving rows in
        // quarantine for repair rather than partly marking or retiring them.
        java.util.HashSet<Long> unique = new java.util.HashSet<>();
        for (Long sequence : sequences) {
            if (sequence == null || sequence <= 0L || !unique.add(sequence) || !active.containsKey(sequence)) {
                return;
            }
        }
        for (Long sequence : sequences) {
            if (sinkSucceeded) {
                Entry existing = active.get(sequence);
                active.put(sequence, new Entry(sequence, existing.action(), true));
            } else {
                active.remove(sequence);
            }
        }
    }

    private static void writeAction(DataOutputStream out, Action a) throws IOException {
        out.writeLong(a.id());
        out.writeLong(a.timestamp());
        out.writeInt(a.type().id());
        writeUuid(out, a.actorUuid());
        writeString(out, a.actorName());
        writeString(out, a.worldId());
        out.writeInt(a.x()); out.writeInt(a.y()); out.writeInt(a.z());
        writeString(out, a.targetId());
        writeString(out, a.targetMeta());
        out.writeInt(a.amount());
        out.writeBoolean(a.rolledBack());
        writeString(out, a.sourceTag());
        writeString(out, a.signSide());
        writeString(out, a.signDyeColor());
        writeNullableBoolean(out, a.signWaxed());
        writeString(out, a.oldBlockState());
        writeString(out, a.newBlockState());
        writeBytes(out, a.blockEntityNbt());
        writeBytes(out, a.itemNbt());
        writeBytes(out, a.entityNbt());
        out.writeLong(a.pairId() == null ? 0L : a.pairId());
        if (MAGIC == MAGIC_V3) {
            out.writeInt(a.inventorySlot() == null ? -1 : a.inventorySlot());
        }
    }

    private static Action readAction(DataInputStream in, boolean withPairId, boolean withInventorySlot) throws IOException {
        long id = in.readLong();
        long timestamp = in.readLong();
        ActionType type = ActionType.byId(in.readInt());
        UUID actorUuid = readUuid(in);
        String actorName = readString(in);
        String worldId = readString(in);
        int x = in.readInt(), y = in.readInt(), z = in.readInt();
        String targetId = readString(in);
        String targetMeta = readString(in);
        int amount = in.readInt();
        boolean rolledBack = in.readBoolean();
        String sourceTag = readString(in);
        String signSide = readString(in);
        String signDyeColor = readString(in);
        Boolean signWaxed = readNullableBoolean(in);
        String oldBlockState = readString(in);
        String newBlockState = readString(in);
        byte[] blockEntityNbt = readBytes(in);
        byte[] itemNbt = readBytes(in);
        byte[] entityNbt = readBytes(in);
        Long pairId = null;
        if (withPairId) {
            long raw = in.readLong();
            pairId = raw == 0L ? null : raw;
        }
        Integer inventorySlot = null;
        if (withInventorySlot) {
            int raw = in.readInt();
            inventorySlot = raw < 0 ? null : raw;
        }
        return new Action(id, timestamp, type, actorUuid, actorName, worldId,
                x, y, z, targetId, targetMeta, amount, rolledBack, sourceTag,
                signSide, signDyeColor, signWaxed, oldBlockState, newBlockState,
                blockEntityNbt, itemNbt, entityNbt, pairId, inventorySlot);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
    }

    private static void writeNullableBoolean(DataOutputStream out, Boolean value) throws IOException {
        out.writeByte(value == null ? 0 : value ? 2 : 1);
    }

    private static Boolean readNullableBoolean(DataInputStream in) throws IOException {
        return switch (in.readByte()) { case 0 -> null; case 1 -> false; case 2 -> true; default -> throw new IOException("invalid nullable boolean"); };
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("quarantine string too large");
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < -1 || length > MAX_STRING_BYTES) throw new IOException("invalid quarantine string length");
        if (length < 0) return null;
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        if (value.length > MAX_BINARY_BYTES) throw new IOException("quarantine binary payload too large");
        out.writeInt(value.length); out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < -1 || length > MAX_BINARY_BYTES) throw new IOException("invalid quarantine binary length");
        if (length < 0) return null;
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }
}
