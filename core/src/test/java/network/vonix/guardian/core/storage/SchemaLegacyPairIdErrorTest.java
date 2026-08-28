package network.vonix.guardian.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Regression contract for legacy MySQL pair_id index errors. */
class SchemaLegacyPairIdErrorTest {
    private static boolean isMissingPairIdColumn(SQLException error) throws Exception {
        Method method = Schema.class.getDeclaredMethod("isMissingPairIdColumn", SQLException.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, error);
    }

    @Test
    void schema_accepts_original_mysql_and_sqlite_missing_pair_id_forms() throws Exception {
        assertThat(isMissingPairIdColumn(new SQLException("legacy", "HY000", 1072))).isTrue();
        assertThat(isMissingPairIdColumn(new SQLException("Key column 'pair_id' doesn't exist"))).isTrue();
        assertThat(isMissingPairIdColumn(new SQLException("Unknown column 'pair_id'"))).isTrue();
        assertThat(isMissingPairIdColumn(new SQLException("no such column: pair_id"))).isTrue();
        assertThat(isMissingPairIdColumn(new SQLException("duplicate key name"))).isFalse();
    }
}
