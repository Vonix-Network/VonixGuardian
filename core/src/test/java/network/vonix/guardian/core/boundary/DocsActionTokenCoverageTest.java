package network.vonix.guardian.core.boundary;

import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published documentation names every action token an operator can type. It is
 * prose, so it drifts: a new {@link ActionType} ships and the reference page silently
 * omits it, leaving an operator unable to filter the very rows the release added.
 *
 * <p>This closes that gap by reading the token table out of the docs and comparing it
 * to the enum. It found three missing tokens on the 2.3.0 release — the page claimed
 * 40 types when 43 shipped.</p>
 *
 * <p>Scope is deliberately the documentation source tree, not the built site: the site
 * is generated from it, and a generated file cannot be missing something its source
 * has.</p>
 */
class DocsActionTokenCoverageTest {

    /** Repo root, found by walking up to the directory that contains the cells. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("mc-1.18.2"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    /**
     * The docs live in a sibling repository. A missing checkout is a skip, not a
     * failure: this guard is useful where the docs tree exists and must not break a
     * build on a machine that only has the mod.
     */
    private static Path actionsPage() {
        Path root = repoRoot();
        Path[] candidates = {
            root.getParent().resolve("vonix-mods-docs/content/vonixguardian/actions.md"),
            root.resolve("docs/ACTIONS.md"),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Every {@code a:} token the reference page lists, without the prefix. */
    private static Set<String> documentedTokens(Path page) throws IOException {
        String text = Files.readString(page);
        Set<String> out = new LinkedHashSet<>();
        // Table rows: | `token` | id | family | dir | ...
        Matcher row = Pattern.compile("\\|\\s*`([+-]?[a-z]+)`\\s*\\|\\s*\\d+\\s*\\|").matcher(text);
        while (row.find()) {
            out.add(row.group(1));
        }
        return out;
    }

    @Test
    void everyActionTypeAppearsInTheReferencePage() throws IOException {
        Path page = actionsPage();
        if (page == null) {
            return; // docs checkout not present on this machine
        }

        Set<String> documented = documentedTokens(page);
        assertThat(documented)
            .as("parsed no tokens from %s; the table format changed and this guard is "
                + "silently checking nothing", page)
            .isNotEmpty();

        Set<String> missing = new LinkedHashSet<>();
        for (ActionType t : ActionType.values()) {
            String token = t.token();
            // Container/inventory/hopper/piston/bucket/hanging/session/item rows are
            // listed with their sign in the table ("+piston"); accept either form.
            if (!documented.contains(token)
                    && !documented.contains(token.replaceFirst("^[+-]", ""))
                    && !documented.contains("+" + token)
                    && !documented.contains("-" + token)) {
                missing.add(token);
            }
        }

        assertThat(missing)
            .as("action types shipped but absent from the reference page — an operator "
                + "cannot filter rows the release added")
            .isEmpty();
    }

    /** The page's own count claim must match the enum. */
    @Test
    void theStatedTokenCountMatchesTheEnum() throws IOException {
        Path page = actionsPage();
        if (page == null) {
            return;
        }
        String text = Files.readString(page);
        Matcher m = Pattern.compile("(\\d+)\\s+(?:canonical\\s+)?action types").matcher(text);
        if (!m.find()) {
            return; // page does not state a count; nothing to contradict
        }
        assertThat(Integer.parseInt(m.group(1)))
            .as("the reference page states a token count that no longer matches ActionType")
            .isEqualTo(ActionType.values().length);
    }
}
