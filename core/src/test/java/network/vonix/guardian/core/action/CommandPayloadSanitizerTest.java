package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPayloadSanitizerTest {

    @Test
    void retainsOnlyCanonicalCommandToken() {
        assertThat(CommandPayloadSanitizer.sanitize("/tell Alice super-secret-token"))
                .isEqualTo("/tell");
        assertThat(CommandPayloadSanitizer.sanitize("  say\t\"private words\"  "))
                .isEqualTo("/say");
        assertThat(CommandPayloadSanitizer.sanitize("\"execute\" as Alice"))
                .isEqualTo("/execute");
        assertThat(CommandPayloadSanitizer.sanitize("\"execute as Alice\" secret"))
                .isEqualTo("/execute");
        assertThat(CommandPayloadSanitizer.sanitize("//say secret"))
                .isEqualTo("/say");
    }

    @Test
    void nullBlankAndSlashOnlyAreEmpty() {
        assertThat(CommandPayloadSanitizer.sanitize(null)).isEmpty();
        assertThat(CommandPayloadSanitizer.sanitize(" \t ")).isEmpty();
        assertThat(CommandPayloadSanitizer.sanitize("/   ")).isEmpty();
    }

    @Test
    void actionBuilderSanitizesCommandBeforeActionConstruction() {
        Action action = new ActionBuilder()
                .type(ActionType.COMMAND)
                .worldId("minecraft:overworld")
                .targetId("/give Alice minecraft:diamond 64")
                .build();

        assertThat(action.targetId()).isEqualTo("/give");
    }

    @Test
    void boundaryRebuildPreservesEveryNonCommandField() {
        byte[] blockNbt = {1, 2};
        byte[] itemNbt = {3, 4};
        byte[] entityNbt = {5, 6};
        Action original = new Action(7L, 8L, ActionType.COMMAND,
                java.util.UUID.randomUUID(), "Alice", "minecraft:overworld",
                1, 2, 3, "/give Alice diamond 64", "meta", 4, true, "source",
                "front", "red", true, "old", "new", blockNbt, itemNbt, entityNbt);

        Action sanitized = CommandPayloadSanitizer.sanitizeForPersistence(original);

        assertThat(sanitized.targetId()).isEqualTo("/give");
        assertThat(sanitized.id()).isEqualTo(original.id());
        assertThat(sanitized.timestamp()).isEqualTo(original.timestamp());
        assertThat(sanitized.actorUuid()).isEqualTo(original.actorUuid());
        assertThat(sanitized.actorName()).isEqualTo(original.actorName());
        assertThat(sanitized.worldId()).isEqualTo(original.worldId());
        assertThat(sanitized.x()).isEqualTo(original.x());
        assertThat(sanitized.y()).isEqualTo(original.y());
        assertThat(sanitized.z()).isEqualTo(original.z());
        assertThat(sanitized.targetMeta()).isEqualTo(original.targetMeta());
        assertThat(sanitized.amount()).isEqualTo(original.amount());
        assertThat(sanitized.rolledBack()).isEqualTo(original.rolledBack());
        assertThat(sanitized.sourceTag()).isEqualTo(original.sourceTag());
        assertThat(sanitized.signSide()).isEqualTo(original.signSide());
        assertThat(sanitized.signDyeColor()).isEqualTo(original.signDyeColor());
        assertThat(sanitized.signWaxed()).isEqualTo(original.signWaxed());
        assertThat(sanitized.oldBlockState()).isEqualTo(original.oldBlockState());
        assertThat(sanitized.newBlockState()).isEqualTo(original.newBlockState());
        assertThat(sanitized.blockEntityNbt()).isSameAs(blockNbt);
        assertThat(sanitized.itemNbt()).isSameAs(itemNbt);
        assertThat(sanitized.entityNbt()).isSameAs(entityNbt);
    }
}
