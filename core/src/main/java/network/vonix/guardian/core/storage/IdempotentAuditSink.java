package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.logfile.JsonLinesLogFile;
import network.vonix.guardian.core.queue.BatchSink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC + JSONL dual-write with a durable outbox.
 *
 * <p>Insert and outbox staging share one JDBC transaction. JSONL append happens
 * after commit; success ACKs the outbox. A retry after JSONL failure skips
 * {@code vg_actions} insertion because the outbox row is still present, so
 * database rows are not duplicated.
 */
public final class IdempotentAuditSink implements BatchSink {

    private static final int WIRE_VERSION = 1;

    private final GuardianDao dao;
    private final AtomicReference<JsonLinesLogFile> logHolder;
    /** Test-only: remaining JSONL appends that should fail before writing. */
    private final AtomicInteger jsonlFailuresRemaining = new AtomicInteger();

    public IdempotentAuditSink(GuardianDao dao, AtomicReference<JsonLinesLogFile> logHolder) {
        this.dao = dao;
        this.logHolder = logHolder;
    }

    /** Deterministic JSONL failure seam for dual-write retry tests. */
    void failNextJsonlAppends(int count) {
        jsonlFailuresRemaining.set(count);
    }

    /** Replay a leftover outbox from a previous process before the queue starts. */
    public void recoverPending() throws Exception {
        byte[] pending = dao.peekSinkOutbox();
        if (pending == null || pending.length == 0) {
            return;
        }
        appendJsonl(decode(pending));
        dao.ackSinkOutbox();
    }

    @Override
    public void flush(List<Action> batch) throws Exception {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        byte[] pending = dao.peekSinkOutbox();
        if (pending != null && pending.length > 0) {
            List<Action> staged = decode(pending);
            appendJsonl(staged);
            dao.ackSinkOutbox();
            if (sameBatch(staged, batch)) {
                return;
            }
        }
        dao.insertBatchWithOutbox(batch, encode(batch));
        appendJsonl(batch);
        dao.ackSinkOutbox();
    }

    private void appendJsonl(List<Action> batch) throws IOException {
        if (jsonlFailuresRemaining.get() > 0) {
            jsonlFailuresRemaining.decrementAndGet();
            throw new IOException("controlled JSONL failure after JDBC commit");
        }
        JsonLinesLogFile log = logHolder == null ? null : logHolder.get();
        if (log == null) {
            return;
        }
        for (Action action : batch) {
            log.appendOrThrow(action);
        }
        log.flushOrThrow();
    }

    static boolean sameBatch(List<Action> left, List<Action> right) throws IOException {
        return Arrays.equals(encode(left), encode(right));
    }

    static byte[] encode(List<Action> batch) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(WIRE_VERSION);
            out.writeInt(batch.size());
            for (Action a : batch) {
                writeAction(out, a);
            }
            out.flush();
        }
        return bos.toByteArray();
    }

    static List<Action> decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readInt();
            if (version != WIRE_VERSION) {
                throw new IOException("unsupported sink outbox version " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IOException("invalid sink outbox size " + count);
            }
            List<Action> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                out.add(readAction(in));
            }
            return out;
        }
    }

    private static void writeAction(DataOutputStream out, Action a) throws IOException {
        out.writeLong(a.id());
        out.writeLong(a.timestamp());
        out.writeInt(a.type().id());
        writeUuid(out, a.actorUuid());
        writeString(out, a.actorName());
        writeString(out, a.worldId());
        out.writeInt(a.x());
        out.writeInt(a.y());
        out.writeInt(a.z());
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
        out.writeInt(a.inventorySlot() == null ? -1 : a.inventorySlot());
    }

    private static Action readAction(DataInputStream in) throws IOException {
        long id = in.readLong();
        long timestamp = in.readLong();
        ActionType type = ActionType.byId(in.readInt());
        UUID actorUuid = readUuid(in);
        String actorName = readString(in);
        String worldId = readString(in);
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
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
        long pairRaw = in.readLong();
        Long pairId = pairRaw == 0L ? null : pairRaw;
        int slotRaw = in.readInt();
        Integer inventorySlot = slotRaw < 0 ? null : slotRaw;
        return new Action(id, timestamp, type, actorUuid, actorName, worldId,
                x, y, z, targetId, targetMeta, amount, rolledBack, sourceTag,
                signSide, signDyeColor, signWaxed, oldBlockState, newBlockState,
                blockEntityNbt, itemNbt, entityNbt, pairId, inventorySlot);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value.getMostSignificantBits());
            out.writeLong(value.getLeastSignificantBits());
        }
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
    }

    private static void writeNullableBoolean(DataOutputStream out, Boolean value) throws IOException {
        out.writeByte(value == null ? 0 : value ? 2 : 1);
    }

    private static Boolean readNullableBoolean(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case 0 -> null;
            case 1 -> false;
            case 2 -> true;
            default -> throw new IOException("invalid nullable boolean");
        };
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        return in.readNBytes(length);
    }
}
