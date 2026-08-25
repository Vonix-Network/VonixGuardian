package network.vonix.guardian.core.storage.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for bounded MySQL socket reads during schema/bootstrap work. */
class MysqlBootstrapTimeoutTest {

    @Test
    void exposes_bounded_connect_and_socket_timeouts_for_mysql_bootstrap() {
        assertThat(MysqlBootstrapPolicy.timeoutProperties())
            .containsEntry("connectTimeout", "15000")
            .containsEntry("socketTimeout", "15000");
    }
}
