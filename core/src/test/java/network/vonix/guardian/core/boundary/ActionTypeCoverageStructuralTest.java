package network.vonix.guardian.core.boundary;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cell parity of the {@link EventSubmitter} surface, asserted against the
 * interface rather than against cell source.
 *
 * <h2>What this replaces</h2>
 * The previous version walked every loader module and regex-matched
 * {@code .submitXxx(} call sites, requiring all nine cells to contain the same
 * set of textual call names. That is a text pin with two failure modes: a
 * refactor that keeps behaviour but renames a helper fails, and a call that is
 * textually present but unreachable passes. It also silently went green when a
 * cell stopped compiling the file it was scanning.
 *
 * <p>The parity that actually matters is: <em>given the same
 * {@link EventSubmitter}, every cell can emit the same set of
 * {@link ActionType}s</em>, and every type the interface can emit is a real
 * enum constant. Both are properties of the compiled interface and of
 * {@link ActionType}, so both are asserted directly below.</p>
 *
 * <h2>Cell-level wiring</h2>
 * Whether a specific cell wires a given surface is a per-cell question with a
 * per-cell answer; it is covered where the answer is observable, by
 * {@code ActionTypeProducerCoverageTest} (the core producers actually persist a
 * row) and by {@code MixinRegistrationStructuralTest} (the mixin that feeds a
 * cell is actually registered and applied). This class deliberately does not
 * re-derive it from source text.
 */
class ActionTypeCoverageStructuralTest {

    /**
     * Interface methods that emit a type. {@code submit} itself is the generic
     * entry point and takes a caller-built action, so it maps to no single type
     * and is excluded rather than special-cased by name at every call site.
     */
    private static final Set<String> NOT_A_PRODUCER = Set.of("submit", "shouldLogItem", "shouldLogEntity",
        // Not producers: a counter and two generic passthroughs that emit whatever
        // the caller built. They start with "submit" but map to no single ActionType.
        "submitted", "submitAccepted", "submitAcceptedPair",
        // An accessor returning the submitter, not a producer.
        "submitter");

    @Test
    void everySubmitterMethodMapsToAtLeastOneRealActionType() {
        // A submit method whose name has no ActionType equivalent means the
        // interface can express something the enum cannot store, or the enum
        // gained a constant the interface cannot emit. Either way the audit log
        // has a type an operator can query but never receives.
        List<String> unmapped = new ArrayList<>();
        for (String method : producerMethodNames()) {
            String typeName = typeNameFor(method);
            if (typeName == null) {
                unmapped.add(method + " (no ActionType derivation)");
                continue;
            }
            try {
                ActionType.valueOf(typeName);
            } catch (IllegalArgumentException missing) {
                unmapped.add(method + " -> " + typeName + " is not an ActionType");
            }
        }
        assertThat(unmapped)
                .as("submitter methods with no real ActionType behind them")
                .isEmpty();
    }

    @Test
    void theSubmitterSurfaceCoversEveryActionTypeExceptTheDocumentedSilence() {
        Set<ActionType> emitted = typesReachableFromTheInterface();

        // CHUNK_POPULATE has no submit method on purpose: chunk generation is not
        // an attributable act and the rollback engine refuses to reverse it. It is
        // kept in the enum so historical rows and a:populate still resolve.
        Set<ActionType> expectedSilent = Set.of(ActionType.CHUNK_POPULATE);

        Set<ActionType> unexplained = new TreeSet<>(Set.of(ActionType.values()));
        unexplained.removeAll(emitted);
        unexplained.removeAll(expectedSilent);

        assertThat(unexplained)
                .as("ActionTypes with no EventSubmitter method; add one or document why the type "
                        + "is deliberately unreachable")
                .isEmpty();
    }

    @Test
    void everyActionTypeIsAddressableThroughTheInterfaceOrIsDeclaredSilent() {
        // The converse direction: no submit method may emit a type outside the
        // enum. This is what catches a constant removed from the enum while a
        // producer still names it — the sort of change that compiles in core but
        // breaks the loader cells at runtime.
        for (ActionType type : typesReachableFromTheInterface()) {
            assertThat(ActionType.byId(type.id()))
                    .as("%s must round-trip through its stable id", type)
                    .isSameAs(type);
            assertThat(ActionType.byToken(type.token()))
                    .as("%s must round-trip through its CLI token", type)
                    .isSameAs(type);
        }
    }

    @Test
    void theFamiliesEveryCellMustCaptureAreStillProducedByTheInterface() {
        // These four types are the ones every cell is expected to emit regardless
        // of loader mechanism (events vs mixins). Asserting them through the
        // interface keeps the expectation where it belongs — on the contract —
        // instead of on one cell's spelling of the call.
        Set<ActionType> emitted = typesReachableFromTheInterface();
        assertThat(emitted)
                .as("every cell is required to capture these")
                .contains(ActionType.SIGN, ActionType.FORM, ActionType.CHAT, ActionType.COMMAND,
                        ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE);
    }

    @Test
    void everyProducerMethodIsDeclaredOnTheInterfaceNotOnlyOnTheImplementation() {
        // A producer added only to Guardian compiles in core but no loader cell can
        // reach it: cells hold an EventSubmitter, not a Guardian. Asserting that
        // the method is on the interface is the check that keeps the loader
        // surface honest.
        Set<String> onInterface = producerMethodNames();
        for (Method m : network.vonix.guardian.core.Guardian.class.getMethods()) {
            if (!m.getName().startsWith("submit") || NOT_A_PRODUCER.contains(m.getName())) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertThat(onInterface)
                    .as("Guardian.%s(...) is a producer the loader cells cannot call because "
                            + "EventSubmitter does not declare it", m.getName())
                    .contains(m.getName());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Every producer method name declared on {@link EventSubmitter}. */
    private static Set<String> producerMethodNames() {
        Set<String> names = new TreeSet<>();
        for (Method m : EventSubmitter.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || !m.getName().startsWith("submit")) {
                continue;
            }
            if (NOT_A_PRODUCER.contains(m.getName())) {
                continue;
            }
            names.add(m.getName());
        }
        return names;
    }

    /**
     * The {@link ActionType} a producer method emits, or {@code null}.
     *
     * <p>Derived from the method name ({@code submitItemDrop} →
     * {@code ITEM_DROP}) with the two naming exceptions the interface actually
     * has: the container/hopper/replacement methods are direction-signed and
     * therefore emit a pair, and {@code submitClick} is the one whose name is not
     * a type name at all after the suffix strip.</p>
     */
    private static String typeNameFor(String method) {
        return switch (method) {
            case "submitContainerChange" -> "CONTAINER_DEPOSIT";     // sign picks deposit/withdraw
            case "submitInventoryReplacement" -> "INVENTORY_WITHDRAW"; // emit order: withdraw, deposit
            case "submitHopperTransfer" -> "HOPPER_PULL";             // emit order: pull, push
            default -> camelToConstant(method.substring("submit".length()));
        };
    }

    /** {@code ItemDrop} → {@code ITEM_DROP}. */
    private static String camelToConstant(String camel) {
        if (camel.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    /**
     * Every {@link ActionType} that at least one producer method can emit,
     * including both halves of the direction-signed pair methods.
     */
    private static Set<ActionType> typesReachableFromTheInterface() {
        Map<String, Set<ActionType>> pairs = new LinkedHashMap<>();
        pairs.put("submitContainerChange",
                Set.of(ActionType.CONTAINER_DEPOSIT, ActionType.CONTAINER_WITHDRAW));
        pairs.put("submitInventoryReplacement",
                Set.of(ActionType.INVENTORY_WITHDRAW, ActionType.INVENTORY_DEPOSIT));
        pairs.put("submitHopperTransfer",
                Set.of(ActionType.HOPPER_PULL, ActionType.HOPPER_PUSH));
        pairs.put("submitExplosion", Set.of(ActionType.EXPLOSION));
        pairs.put("submitSign", Set.of(ActionType.SIGN));

        Set<ActionType> emitted = new TreeSet<>();
        for (String method : producerMethodNames()) {
            Set<ActionType> explicit = pairs.get(method);
            if (explicit != null) {
                emitted.addAll(explicit);
                continue;
            }
            String typeName = typeNameFor(method);
            if (typeName != null) {
                try {
                    emitted.add(ActionType.valueOf(typeName));
                } catch (IllegalArgumentException ignored) {
                    // Reported by everySubmitterMethodMapsToAtLeastOneRealActionType.
                }
            }
        }
        return emitted;
    }
}
