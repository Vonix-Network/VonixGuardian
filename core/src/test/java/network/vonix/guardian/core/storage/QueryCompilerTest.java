package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCompilerTest {

    @Test
    void empty_filter_emits_no_where() {
        QueryCompiler.Compiled c = QueryCompiler.compileSelect(QueryFilter.empty(), 0, 50);
        assertThat(c.sql())
            .contains("SELECT ")
            .contains(" FROM vg_actions a ")
            .doesNotContain(" WHERE ")
            .contains("ORDER BY a.ts DESC")
            .endsWith("LIMIT ? OFFSET ?");
        assertThat(c.binds()).containsExactly(50, 0);
    }

    @Test
    void user_uuid_and_time_window_bind_in_order() {
        UUID u = UUID.fromString("00000000-0000-0000-0000-000000000001");
        QueryFilter f = QueryFilter.builder()
            .addUser(new QueryFilter.UserSel(u, "Notch", false))
            .sinceMillis(1000L)
            .untilMillis(2000L)
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileSelect(f, 5, 10);
        assertThat(c.sql())
            .contains("(u.uuid = ?)")
            .contains("a.ts >= ?")
            .contains("a.ts <= ?");
        assertThat(c.binds()).containsExactly(u.toString(), 1000L, 2000L, 10, 5);
    }

    @Test
    void radius_emits_bounding_box() {
        QueryFilter f = QueryFilter.builder()
            .radius(8)
            .center(100, 64, -50)
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql())
            .startsWith("SELECT COUNT(*)")
            .contains("a.x BETWEEN ? AND ?")
            .contains("a.z BETWEEN ? AND ?")
            .contains("a.y BETWEEN ? AND ?");
        assertThat(c.binds()).containsExactly(
            100 - 8, 100 + 8,
            -50 - 8, -50 + 8,
            64 - 8, 64 + 8
        );
    }

    @Test
    void action_types_use_in_with_ids() {
        QueryFilter f = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.ANY))
            .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_BREAK, QueryFilter.ActionSelect.Sign.ANY))
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("a.type IN (?,?)");
        assertThat(c.binds()).containsExactly(
            ActionType.BLOCK_PLACE.id(),
            ActionType.BLOCK_BREAK.id()
        );
    }

    @Test
    void include_and_exclude_lists_use_in_and_not_in() {
        QueryFilter f = QueryFilter.builder()
            .addInclude("minecraft:stone")
            .addInclude("minecraft:oak_log")
            .addExclude("minecraft:dirt")
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql())
            .contains("a.target IN (?,?)")
            .contains("a.target NOT IN (?)");
        assertThat(c.binds()).containsExactly("minecraft:stone", "minecraft:oak_log", "minecraft:dirt");
    }

    @Test
    void world_sel_explicit_filters_world_key() {
        QueryFilter f = QueryFilter.builder()
            .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("w.world_key = ?");
        assertThat(c.binds()).containsExactly("minecraft:the_nether");
    }

    @Test
    void global_world_sel_does_not_constrain() {
        QueryFilter f = QueryFilter.builder()
            .worldSel(new QueryFilter.WorldSel(null, true))
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).doesNotContain("w.world_key");
        assertThat(c.binds()).isEmpty();
    }

    @Test
    void compile_delete_wraps_in_subquery() {
        QueryFilter f = QueryFilter.builder().sinceMillis(1L).build();
        QueryCompiler.Compiled c = QueryCompiler.compileDelete(f);
        assertThat(c.sql())
            .startsWith("DELETE FROM vg_actions WHERE id IN (SELECT a.id")
            .endsWith(")");
        assertThat(c.binds()).containsExactly(1L);
    }

    @Test
    void column_whitelist_contains_all_referenced_columns() {
        assertThat(QueryCompiler.COLUMN_WHITELIST)
            .contains("ts", "type", "user_id", "world_id", "x", "y", "z", "target", "rolled_back");
    }

    @Test
    void sentinel_name_user_binds_name_only() {
        QueryFilter f = QueryFilter.builder()
            .addUser(new QueryFilter.UserSel(null, "#creeper", true))
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("(u.name = ?)");
        assertThat(c.binds()).containsExactly("#creeper");
    }

    @Test
    void mixed_users_combine_with_or() {
        UUID u = UUID.fromString("11111111-1111-1111-1111-111111111111");
        QueryFilter f = QueryFilter.builder()
            .addUser(new QueryFilter.UserSel(u, "Bob", false))
            .addUser(new QueryFilter.UserSel(null, "#tnt", true))
            .build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("(u.uuid = ? OR u.name = ?)");
        assertThat(c.binds()).containsExactly(u.toString(), "#tnt");
    }

    @Test
    void rolled_back_null_emits_no_clause() {
        QueryFilter f = QueryFilter.builder().rolledBack(null).build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).doesNotContain("rolled_back");
        assertThat(c.binds()).isEmpty();
    }

    @Test
    void rolled_back_true_binds_one() {
        QueryFilter f = QueryFilter.builder().rolledBack(Boolean.TRUE).build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("a.rolled_back = ?");
        assertThat(c.binds()).containsExactly(1);
    }

    @Test
    void rolled_back_false_binds_zero() {
        QueryFilter f = QueryFilter.builder().rolledBack(Boolean.FALSE).build();
        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);
        assertThat(c.sql()).contains("a.rolled_back = ?");
        assertThat(c.binds()).containsExactly(0);
    }
}
