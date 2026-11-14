/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.action.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side seam for handing a finished lookup page to a companion client
 * mod, so an operator can read results in a native list UI instead of a wall
 * of chat lines.
 *
 * <p><b>Scope.</b> This type is a <em>contract only</em>. It declares what a
 * client would receive and how the server behaves when nothing is listening.
 * It deliberately contains no packet class, no registry entry, no protocol
 * version negotiation and no client code: a loader cell supplies those later
 * by implementing this interface. Nothing in {@code core} knows how bytes
 * reach a client, and nothing here may assume a particular Minecraft version.
 *
 * <p><b>Not third-party API.</b> This type shares the
 * {@code network.vonix.guardian.core.api} package with
 * {@link VonixGuardianAPI}, but it is a seam <em>inside</em> the server, not
 * an integration point for other people's mods. It is not covered by
 * {@code VonixGuardianAPI.apiVersion()} and carries no soft-dep reflection
 * contract; only VonixGuardian's own loader cells implement it. It does follow
 * the package rule that methods may be added while existing signatures and
 * record shapes stay put, which is why {@link #CONTRACT_VERSION} exists.
 *
 * <h2>What a client receives</h2>
 *
 * <p>One {@link LookupPage} per lookup the player runs: the viewer's UUID, the
 * raw filter text they typed, the 1-based page number, the page size, the row
 * count when the server knows it ({@code -1} when it does not — see
 * {@link LookupPage#totalRows()}), a {@code hasNext} flag, and a list of
 * {@link Row} values in newest-first order. Rows are transport-agnostic value
 * data: no {@link Action} reference, no NBT payload, no storage column names.
 * A cell may encode them however its protocol likes; the encoder is free, the
 * shape is not (see {@link #CONTRACT_VERSION}).
 *
 * <p><b>Rows arrive already filtered.</b> The caller passes the page that
 * {@code LookupPermissionFilter.visiblePage} produced, not a raw query result.
 * A row the viewer may not see is never in the payload, so a native UI cannot
 * leak what chat would have hidden. A sink that receives rows is trusted not
 * to widen them and MUST send them to {@link LookupPage#viewer()} only —
 * never to another player, never as a broadcast.
 *
 * <p><b>Rows are read-only.</b> A native UI may offer buttons, but every
 * destructive action it triggers must be an ordinary command sent through the
 * normal command path (for example {@code /vg rollback ...}), so the same
 * permission checks and confirmation rules apply. The payload carries no
 * capability to mutate the world, and a sink must not invent one.
 *
 * <h2>What happens when no client is listening</h2>
 *
 * <p>Nothing. {@link #NONE} is the null-object default: {@link #isAvailable()}
 * returns {@code false}, {@link #deliverLookup} returns
 * {@link Outcome#UNSUPPORTED} without touching the page, and
 * {@link #describe()} returns {@link #NO_CLIENT_NETWORKING} — the literal
 * words "client result delivery: no client networking (chat only)". Callers
 * are expected to reach for {@link #orNone} so a missing sink is a value
 * rather than a {@code null} check.
 *
 * <h2>Chat is the always-available path</h2>
 *
 * <p>Chat rendering is the contract of record, not the fallback. A lookup must
 * remain readable with no client mod installed, no handshake completed and no
 * network connection beyond the vanilla one. The only outcome that permits a
 * caller to skip the chat page for a request is {@link Outcome#DELIVERED}, and
 * only for that one viewer and that one request: {@link Outcome#UNSUPPORTED}
 * and {@link Outcome#FAILED} both require the chat page to be rendered exactly
 * as it is today. A sink MUST NOT disable, redirect or suppress the chat path
 * for any other player, and a failure MUST NOT leave the operator staring at
 * an empty screen. One failed delivery is logged once and then forgotten;
 * never retried in a loop.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #deliverLookup} is called from the same worker thread that ran the
 * query, i.e. <em>not</em> the server thread. An implementation therefore must
 * not block on the server thread, must not touch world state, and must not
 * wait for a network write to flush. Encode, hand the payload to the loader's
 * own scheduler, and return. The page values are immutable, so a sink may
 * retain them.
 *
 * <h2>Adding to this contract</h2>
 *
 * <p>{@link #CONTRACT_VERSION} covers the shape of {@link LookupPage} and
 * {@link Row} only. New payload kinds (rollback progress, entity inspect) are
 * added as new {@code deliverX} methods within the same version, following the
 * package rule that methods may be added but existing signatures and record
 * shapes may not change.
 *
 * <p>Any such method MUST be declared {@code default} and return
 * {@link Outcome#UNSUPPORTED}, so a cell written against today's interface
 * keeps compiling and keeps its current chat-only behaviour until it chooses
 * to implement the new payload. Adding an abstract method here would break
 * every existing cell at compile time and is therefore a
 * {@link #CONTRACT_VERSION} bump, not an addition.
 *
 * <h2>Wiring, for the cell that implements this</h2>
 *
 * <p>This interface has no installer and no registry on purpose: neither
 * belongs in {@code core}, and inventing one here would pick a loader's
 * lifecycle for it. A cell that adds packets is expected to do four things,
 * none of which require changing this file:
 *
 * <ol>
 *   <li>Implement this interface in the loader module, holding a reference to
 *       whichever channel object that loader's networking API provides.</li>
 *   <li>Keep one instance behind a {@code volatile} field initialised to
 *       {@link #NONE}, and swap it for the implementation during mod
 *       construction — before any command can run. Readers use
 *       {@link #orNone} so a publication race degrades to chat rather than to
 *       a {@code NullPointerException}.</li>
 *   <li>At the point where the cell today builds its chat page, build a
 *       {@link LookupPage} from the same {@code visiblePage} rows, call
 *       {@link #deliverLookup}, and render the chat page unless the answer is
 *       {@link Outcome#DELIVERED}.</li>
 *   <li>Decline delivery when the client's advertised contract version is not
 *       {@link #CONTRACT_VERSION}; the client then falls back to chat like any
 *       other unsupported case.</li>
 * </ol>
 *
 * <p>A cell that ships no client mod keeps {@link #NONE} and changes nothing
 * else — that is the whole point of the null object.
 *
 * @since 2.3.0 (wave 2, lane 27)
 */
public interface ClientResultSink {

    /**
     * Version of the {@link LookupPage} / {@link Row} data shape that this
     * interface defines.
     *
     * <p>A future wire protocol pins its encoding to this number. Bump it only
     * when a field is removed, renamed, or its meaning changes; adding a new
     * payload method does not bump it. A cell whose client speaks a different
     * version must decline the delivery rather than send a payload the client
     * will misread.
     */
    int CONTRACT_VERSION = 1;

    /**
     * The exact words a server with no client networking reports. Kept as one
     * constant so {@link #NONE} and the {@link #describe()} default cannot
     * drift apart, and so status output is greppable in a support log.
     */
    String NO_CLIENT_NETWORKING = "client result delivery: no client networking (chat only)";

    /**
     * The null object: a sink for a server with no client networking.
     *
     * <p>{@link #isAvailable()} is {@code false}, {@link #deliverLookup}
     * returns {@link Outcome#UNSUPPORTED} without reading the page, and
     * {@link #describe()} says so in words. Safe to call from anywhere, any
     * number of times, on any thread; every method is constant and does no
     * work.
     *
     * <p>This is what a cell holds before its own implementation is installed,
     * and what {@link #orNone} returns for {@code null}. It is an anonymous
     * implementation rather than a named nested type so there is no public
     * constructor for a second instance: {@code ClientResultSink.NONE} is the
     * only null sink that can exist, which is what makes an identity check
     * against it meaningful.
     */
    ClientResultSink NONE = new ClientResultSink() {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Outcome deliverLookup(LookupPage page) {
            // No capability, so no attempt: UNSUPPORTED, not FAILED. The page
            // is not read, so a caller pays nothing for calling us.
            return Outcome.UNSUPPORTED;
        }

        @Override
        public String describe() {
            return NO_CLIENT_NETWORKING;
        }

        @Override
        public String toString() {
            return "ClientResultSink.NONE";
        }
    };

    /**
     * Result of one delivery attempt.
     *
     * <p>The distinction between "not applicable" and "tried and broke"
     * matters: the first is the normal state of a server with no client mods
     * and must be silent, the second is a bug worth one log line.
     */
    enum Outcome {

        /**
         * The page was handed to the viewer's client mod. The caller MAY skip
         * the chat rendering of this page for this viewer for this request —
         * and only this page, viewer and request. Everything else about the
         * command (errors, confirmations, follow-up hints) still goes to chat.
         */
        DELIVERED,

        /**
         * Nothing was sent and nothing will be. Either no sink is installed,
         * the viewer has no compatible client mod, or the handshake never
         * completed. The caller MUST render the chat page. This is the
         * expected outcome on most servers and is not an error.
         */
        UNSUPPORTED,

        /**
         * A delivery was attempted and did not complete — encoding error,
         * closed connection, timeout. The caller MUST render the chat page so
         * the operator still sees the results. The sink MUST NOT throw; it
         * reports the failure here and the caller logs it once.
         */
        FAILED;

        /**
         * @return {@code true} only for {@link #DELIVERED} — the single
         *         outcome under which the caller may omit the chat page
         */
        public boolean suppressesChatPage() {
            return this == DELIVERED;
        }
    }

    /**
     * One lookup hit, flattened for transport.
     *
     * <p>This is a projection of {@link Action}, not a reference to it: the
     * storage record carries multi-kilobyte NBT blobs, pair ids and columns a
     * list UI has no use for, and none of that belongs on the wire. Build rows
     * with {@link #of} so the token spelling stays identical to the chat
     * renderer's.
     *
     * @param id          database row id, or {@code -1} when the row is not yet
     *                    persisted; a client may offer "copy id" only when
     *                    {@code id >= 0}
     * @param timestamp   epoch millis (UTC) of the event; the client chooses
     *                    how to format it
     * @param actorUuid   actor UUID, or {@code null} for non-player sources
     *                    (creeper, TNT, lava, ...)
     * @param actorName   resolved name at event time, or a sentinel such as
     *                    {@code "#creeper"}; never {@code null}, never blank
     *                    ({@code "#unknown"} is substituted)
     * @param action      lowercase action verb token with any {@code +}/{@code -}
     *                    sign prefix stripped ({@code "block"}, {@code "container"},
     *                    {@code "chat"}, ...); never {@code null}
     * @param targetId    affected block / item / entity / message id, or
     *                    {@code null} when the event has no target
     * @param amount      stack count for item and container events; {@code 1}
     *                    for single-object events, {@code 0} when not applicable
     * @param sourceTag   optional classifier such as {@code "explosion:tnt"},
     *                    or {@code null}
     * @param worldId     world / dimension key, or {@code null} for
     *                    non-positional events
     * @param positional  whether {@code x/y/z} carry meaning; when
     *                    {@code false} the coordinates are zero and must not be
     *                    shown, linked or offered as a teleport target
     * @param x           block X
     * @param y           block Y
     * @param z           block Z
     * @param rolledBack  whether this event has already been undone; a client
     *                    should mark such rows rather than hide them
     */
    record Row(
            long id,
            long timestamp,
            UUID actorUuid,
            String actorName,
            String action,
            String targetId,
            int amount,
            String sourceTag,
            String worldId,
            boolean positional,
            int x, int y, int z,
            boolean rolledBack
    ) {

        /** Sentinel name used when the actor is unknown, matching {@link Action}. */
        private static final String UNKNOWN_ACTOR = "#unknown";

        /**
         * Fallback action token for a row built without a type. It is not a
         * real {@link network.vonix.guardian.core.action.ActionType#token()},
         * so a client can tell an unreadable row from a genuine one instead of
         * silently mistranslating it. {@link #of} never produces it, because
         * {@link Action} guarantees a non-null type; only a caller that
         * constructs a {@code Row} by hand can.
         */
        private static final String UNKNOWN_ACTION = "unknown";

        public Row {
            actorName = actorName == null || actorName.isBlank() ? UNKNOWN_ACTOR : actorName;
            action = action == null || action.isBlank() ? UNKNOWN_ACTION : action;
        }

        /**
         * Projects a storage row onto the transport shape.
         *
         * <p>The {@code action} string is {@link
         * network.vonix.guardian.core.action.ActionType#token()} with the
         * leading {@code +} or {@code -} sign removed, which is the same
         * spelling the chat formatter uses in its result records. Keeping one
         * projection means a client and the chat line can never disagree about
         * what happened.
         *
         * @param action storage row; must not be {@code null}
         * @return the transport projection, never {@code null}
         */
        public static Row of(Action action) {
            Objects.requireNonNull(action, "action");
            // Action guarantees a non-null type (see its class Javadoc), the
            // same assumption GuardianAPI makes when it maps rows.
            return new Row(
                    action.id(),
                    action.timestamp(),
                    action.actorUuid(),
                    action.actorName(),
                    stripSign(action.type().token()),
                    action.targetId(),
                    action.amount(),
                    action.sourceTag(),
                    action.worldId(),
                    action.isPositional(),
                    action.x(), action.y(), action.z(),
                    action.rolledBack());
        }

        private static String stripSign(String token) {
            if (token == null || token.isEmpty()) {
                return token;
            }
            char first = token.charAt(0);
            return first == '+' || first == '-' ? token.substring(1) : token;
        }
    }

    /**
     * One page of lookup results addressed to exactly one viewer.
     *
     * <p>Produced by the caller after permission filtering and paging, from the
     * same row list the chat formatter would render. The ordering contract is
     * the chat ordering: newest first, page {@code 1} is the newest slice.
     *
     * @param viewer      the player whose request produced this page; the sink
     *                    sends the page to this connection and no other
     * @param filterText  the raw filter argument text the player typed, or
     *                    {@code null} when they typed none. A client re-issues
     *                    the ordinary {@code /vg lookup <page> <filter>} command
     *                    to page, so it must not synthesise its own query
     * @param page        1-based page number, already clamped to {@code >= 1}
     * @param pageSize    rows per page the server used; a page never exceeds it
     * @param totalRows   total matching rows, or {@code -1} when the server
     *                    answered {@code hasNext} with a page-plus-one probe
     *                    instead of a COUNT. Clients must not render
     *                    {@code -1} as a number
     * @param hasNext     whether at least one further page exists
     * @param rows        immutable, newest first, at most {@code pageSize}
     *                    entries; already permission-filtered for
     *                    {@code viewer}
     */
    record LookupPage(
            UUID viewer,
            String filterText,
            int page,
            int pageSize,
            long totalRows,
            boolean hasNext,
            List<Row> rows
    ) {

        public LookupPage {
            Objects.requireNonNull(viewer, "viewer");
            page = Math.max(1, page);
            pageSize = Math.max(1, pageSize);
            if (totalRows < 0) {
                totalRows = -1L;
            }
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /**
         * Builds a page straight from the storage rows a cell already holds,
         * so the projection to {@link Row} happens in exactly one place.
         *
         * <p>Use this at the call site that today calls the chat formatter: it
         * takes the same {@code visiblePage.rows()} list and produces the same
         * ordering, so the native list and the chat page cannot drift apart.
         * {@code null} entries are skipped rather than failing a whole page.
         *
         * @param viewer     the player who ran the lookup; must not be
         *                   {@code null}
         * @param filterText raw filter argument text, or {@code null}
         * @param page       1-based page number
         * @param pageSize   rows per page the server used
         * @param totalRows  total matches, or {@code -1} when unknown
         * @param hasNext    whether a further page exists
         * @param actions    permission-filtered rows, newest first; must not be
         *                   {@code null}
         * @return the transport page, never {@code null}
         */
        public static LookupPage of(UUID viewer, String filterText, int page, int pageSize,
                                    long totalRows, boolean hasNext, List<Action> actions) {
            Objects.requireNonNull(actions, "actions");
            List<Row> projected = new ArrayList<>(actions.size());
            for (Action action : actions) {
                if (action != null) {
                    projected.add(Row.of(action));
                }
            }
            return new LookupPage(viewer, filterText, page, pageSize, totalRows, hasNext, projected);
        }

        /**
         * @return {@code true} when this page has nothing to show; a sink
         *         should still deliver it so the client can clear its list
         *         rather than showing a stale previous page
         */
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * Whether this server has any client delivery capability at all.
     *
     * <p>Intended as a cheap gate: a caller checks this before building a
     * {@link LookupPage} so the null object costs one virtual call instead of
     * a list copy. Returning {@code true} is not a promise that any particular
     * viewer can be reached — {@link #deliverLookup} may still answer
     * {@link Outcome#UNSUPPORTED}.
     *
     * @return {@code true} if a cell has installed a working sink;
     *         {@code false} for {@link #NONE}
     */
    boolean isAvailable();

    /**
     * Offers a finished lookup page to the viewer's client mod.
     *
     * <p>Must be non-blocking, must not touch world state, and must not throw:
     * a problem is reported as {@link Outcome#FAILED} so the caller can fall
     * back to chat. Implementations MUST send only to
     * {@link LookupPage#viewer()} and SHOULD decline pages whose contract
     * version their client cannot read.
     *
     * @param page the filtered, paged result set; must not be {@code null}
     * @return {@link Outcome#DELIVERED} when the page reached the client,
     *         {@link Outcome#UNSUPPORTED} when no client could accept it,
     *         {@link Outcome#FAILED} when an attempt did not complete
     */
    Outcome deliverLookup(LookupPage page);

    /**
     * One-line description for operator-facing status output.
     *
     * <p>The default answers from {@link #isAvailable()} because this
     * interface cannot know what a cell did. {@link #NONE} overrides it with
     * {@link #NO_CLIENT_NETWORKING}, the exact words a server with no client
     * networking should show.
     *
     * @return a short human-readable status string, never {@code null}
     */
    default String describe() {
        return isAvailable()
                ? "client result delivery: available"
                : NO_CLIENT_NETWORKING;
    }

    /**
     * Null-coalescing helper so callers can hold a possibly-absent sink without
     * branching on {@code null} at every call site.
     *
     * @param sink a sink, or {@code null}
     * @return {@code sink} when non-null, otherwise {@link #NONE}
     */
    static ClientResultSink orNone(ClientResultSink sink) {
        return sink == null ? NONE : sink;
    }
}
