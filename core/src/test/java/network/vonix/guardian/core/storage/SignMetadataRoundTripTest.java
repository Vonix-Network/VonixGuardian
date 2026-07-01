package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3-B15: verify sign-side / dye-color / waxed metadata round-trips through
 * the DAO. Reproduces the CoreProtect-v24 parity gap called out in
 * COREPROTECT-COMPARISON.md — /vg lookup for a sign row must be able to answer
 * "front side, red text, waxed" as well as CoreProtect can.
 */
class SignMetadataRoundTripTest {

    private SqliteDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void sign_metadata_round_trips_through_dao() throws Exception {
        UUID actor = UUID.nameUUIDFromBytes("Signee".getBytes());
        Action sign = new ActionBuilder()
            .type(ActionType.SIGN)
            .actorUuid(actor)
            .actorName("Signee")
            .worldId("minecraft:overworld")
            .position(10, 64, 20)
            .targetId("Hello\nWorld\n\n")
            .signSide("front")
            .signDyeColor("red")
            .signWaxed(true)
            .timestamp(1_700_000_000_000L)
            .build();

        assertThat(dao.insertBatch(List.of(sign))).isEqualTo(1);

        List<Action> rows = dao.query(QueryFilter.empty(), 0, 10);
        assertThat(rows).hasSize(1);
        Action back = rows.get(0);
        assertThat(back.type()).isEqualTo(ActionType.SIGN);
        assertThat(back.targetId()).isEqualTo("Hello\nWorld\n\n");
        assertThat(back.signSide()).isEqualTo("front");
        assertThat(back.signDyeColor()).isEqualTo("red");
        assertThat(back.signWaxed()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void back_side_and_waxed_false_round_trip() throws Exception {
        UUID actor = UUID.nameUUIDFromBytes("BackSigner".getBytes());
        Action sign = new ActionBuilder()
            .type(ActionType.SIGN)
            .actorUuid(actor)
            .actorName("BackSigner")
            .worldId("minecraft:overworld")
            .position(1, 2, 3)
            .targetId("back text")
            .signSide("back")
            .signDyeColor("lime")
            .signWaxed(false)
            .build();

        dao.insertBatch(List.of(sign));
        Action back = dao.query(QueryFilter.empty(), 0, 10).get(0);
        assertThat(back.signSide()).isEqualTo("back");
        assertThat(back.signDyeColor()).isEqualTo("lime");
        assertThat(back.signWaxed()).isEqualTo(Boolean.FALSE);
    }
}
