package network.vonix.guardian.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Regression contract for legacy MySQL pair_id index errors. */
class SchemaLegacyPairIdErrorTest {
    @Test
    void schema_source_retains_all_missing_pair_id_forms() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/java/network/vonix/guardian/core/storage/Schema.java")));
        assertThat(source).contains("MYSQL_ERR_KEY_COLUMN_DOES_NOT_EXIST");
        assertThat(source).contains("e.getErrorCode() == MYSQL_ERR_KEY_COLUMN_DOES_NOT_EXIST");
        assertThat(source).contains("lower.contains(\"doesn\\'t exist\")");
        assertThat(source).contains("lower.contains(\"does not exist\")");
    }
}
