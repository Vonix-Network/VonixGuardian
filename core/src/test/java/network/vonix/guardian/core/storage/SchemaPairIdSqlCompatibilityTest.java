package network.vonix.guardian.core.storage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for the MySQL pre-v6 pair_id index compatibility path. */
class SchemaPairIdSqlCompatibilityTest {

    @Test
    void recognizes_mysql_missing_pair_id_key_column_error() throws Exception {
        Method method = Schema.class.getDeclaredMethod("isMissingPairIdColumn", SQLException.class);
        method.setAccessible(true);

        SQLException mysqlMissingKeyColumn = new SQLException(
                "Key column 'pair_id' doesn't exist in table",
                "42000",
                1072);

        assertThat((Boolean) method.invoke(null, mysqlMissingKeyColumn)).isTrue();
    }
}
