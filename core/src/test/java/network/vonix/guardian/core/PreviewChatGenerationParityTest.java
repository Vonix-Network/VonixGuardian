package network.vonix.guardian.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewChatGenerationParityTest {

    private static final List<String> COMMAND_FILES = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
            "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java",
            "mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common/GuardianCommands.java"
    );

    @Test
    void everyPreviewApplyResponseIsGenerationGuarded() throws Exception {
        Path repo = repoRoot();
        for (String relative : COMMAND_FILES) {
            String source = Files.readString(repo.resolve(relative));
            int start = source.indexOf("public static int apply(CommandContext<CommandSourceStack> ctx, Guardian g)");
            int end = source.indexOf("public static int cancel(CommandContext<CommandSourceStack> ctx, Guardian g)", start);
            assertThat(start).as("Preview.apply in %s", relative).isGreaterThanOrEqualTo(0);
            assertThat(end).as("Preview.cancel in %s", relative).isGreaterThan(start);

            String apply = source.substring(start, end);
            assertThat(count(apply, "CommandChatGuard.next(actor)"))
                    .as("one apply generation in %s", relative).isEqualTo(1);
            assertThat(count(apply, "server.execute("))
                    .as("four async apply responses in %s", relative).isEqualTo(4);
            assertThat(count(apply, "CommandChatGuard.isCurrent(actor, chatGen)"))
                    .as("every async apply response guarded in %s", relative).isEqualTo(4);
            assertThat(apply).as("no direct unguarded async apply response in %s", relative)
                    .doesNotContain("server.execute(() -> send");
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    private static Path repoRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null && !Files.isDirectory(cursor.resolve("mc-1.18.2"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
        }
        return cursor;
    }
}
