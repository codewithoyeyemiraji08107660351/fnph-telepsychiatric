package com.fnph.telepsychiatric.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automated form of "drop the database and replay the migrations from
 * zero". If this cannot rebuild the schema on a blank server, neither can a
 * disaster recovery procedure.
 */
class MigrationReplayTest extends AbstractMigratedDatabaseTest {

    @Test
    @DisplayName("every migration applies cleanly to an empty database")
    void allMigrationsApply() {
        MigrationInfo[] applied = Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .load()
                .info()
                .applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(info ->
                assertThat(info.getState().isFailed()).isFalse());
    }

    @Test
    @DisplayName("migration checksums match the files on disk")
    void checksumsValidate() {
        // Throws if an applied migration was edited after the fact. Editing an
        // applied migration is the most common way a team ends up with two
        // environments that silently differ.
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .load()
                .validate();
    }

    @Test
    @DisplayName("seed data landed and is complete")
    void seedDataApplied() {
        JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);

        Integer centres = jdbc.queryForObject("SELECT COUNT(*) FROM centres", Integer.class);
        assertThat(centres).as("23 Kaduna State LGA Centres of Excellence").isEqualTo(23);

        Integer wallets = jdbc.queryForObject("SELECT COUNT(*) FROM wallets", Integer.class);
        assertThat(wallets).as("every centre gets a wallet at creation").isEqualTo(centres);

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM centres WHERE is_active = 1", Integer.class);
        assertThat(active).as("centres are created inactive and activated deliberately").isZero();
    }

    @Test
    @DisplayName("no migration has been renumbered or duplicated")
    void versionsAreUniqueAndOrdered() {
        List<String> versions = new JdbcTemplate(DATA_SOURCE).queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);

        assertThat(versions).doesNotHaveDuplicates();
        assertThat(versions).isSorted();
    }
}
