package com.fnph.telepsychiatric.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

/**
 * Starts a real MySQL 8.4 container and runs the migrations from zero.
 *
 * Deliberately a container rather than H2. The schema relies on MySQL
 * behaviour the acceptance criteria depend on: BIT columns, multiple NULLs
 * permitted under a unique index, check constraints, InnoDB row locking. H2 in
 * MySQL compatibility mode passes tests that MySQL fails, which is worse than
 * having no test at all.
 *
 * The container command mirrors docker/mysql/fnph.cnf. If those two drift, the
 * tests stop describing production.
 */
public abstract class AbstractMigratedDatabaseTest {

    protected static MySQLContainer<?> MYSQL;
    protected static DataSource DATA_SOURCE;

    @BeforeAll
    static void startAndMigrate() {
        if (MYSQL != null && MYSQL.isRunning()) {
            return;
        }
        MYSQL = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("telepsychiatric")
                .withUsername("fnph_app")
                .withPassword("apppass")
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_unicode_ci",
                        "--default-time-zone=+00:00",
                        "--sql-mode=STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                                + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION");
        MYSQL.start();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8");
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        DATA_SOURCE = ds;

        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .migrate();
    }

    protected static String database() {
        return MYSQL.getDatabaseName();
    }
}
