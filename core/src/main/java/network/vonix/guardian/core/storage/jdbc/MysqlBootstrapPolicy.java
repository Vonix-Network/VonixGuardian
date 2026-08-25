package network.vonix.guardian.core.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;

import java.util.Map;

/** MySQL Connector/J network bounds required for safe server bootstrap. */
final class MysqlBootstrapPolicy {

    static final String TIMEOUT_MS = "15000";

    private MysqlBootstrapPolicy() {}

    static Map<String, String> timeoutProperties() {
        return Map.of(
            "connectTimeout", TIMEOUT_MS,
            "socketTimeout", TIMEOUT_MS
        );
    }

    static void apply(HikariConfig config) {
        timeoutProperties().forEach(config::addDataSourceProperty);
    }
}
