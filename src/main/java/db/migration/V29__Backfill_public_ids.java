package db.migration;

import com.fnph.telepsychiatric.common.PublicId;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every row a public id.
 *
 * Version 29: version 28 is taken by the local V28__bootstrap_administrator.sql.
 * That migration also inserts a row directly, so it runs first and is covered here.
 *
 * V3 added public_id to the existing tables as a nullable column and nothing
 * filled it in. The application assigns an id when it inserts a row, but rows
 * inserted by migrations (the 23 seeded Centres of Excellence, seeded rooms,
 * roles, consent drafts and question sets) never got one. Every API response
 * returned publicId null for them, so nothing could select or act on them by
 * id: a broadcast to a centre sent the centre's name instead and got 404.
 *
 * Java rather than SQL so the ids are real ULIDs from the same generator the
 * application uses, and so the table list comes from the live schema rather
 * than a hand-written list that a later rename would break. Only rows that
 * still have no id are touched; running against a database with none missing
 * changes nothing.
 */
public class V29__Backfill_public_ids extends BaseJavaMigration {

    private static final String TABLES_WITH_PUBLIC_ID = """
            SELECT c.TABLE_NAME
              FROM information_schema.COLUMNS c
              JOIN information_schema.TABLES t
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
               AND t.TABLE_TYPE = 'BASE TABLE'
             WHERE c.TABLE_SCHEMA = DATABASE()
               AND c.COLUMN_NAME = 'public_id'
               AND EXISTS (SELECT 1 FROM information_schema.COLUMNS k
                            WHERE k.TABLE_SCHEMA = c.TABLE_SCHEMA
                              AND k.TABLE_NAME = c.TABLE_NAME
                              AND k.COLUMN_NAME = 'id')
             ORDER BY c.TABLE_NAME
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        List<String> tables = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(TABLES_WITH_PUBLIC_ID);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }

        for (String table : tables) {
            // Names come from information_schema, but they are spliced into SQL,
            // so anything unexpected is skipped rather than trusted.
            if (!table.matches("[A-Za-z0-9_]+")) {
                continue;
            }
            List<Long> missing = new ArrayList<>();
            try (Statement select = connection.createStatement();
                 ResultSet rows = select.executeQuery(
                         "SELECT id FROM `" + table + "` WHERE public_id IS NULL")) {
                while (rows.next()) {
                    missing.add(rows.getLong(1));
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE `" + table + "` SET public_id = ? WHERE id = ? AND public_id IS NULL")) {
                for (Long id : missing) {
                    update.setString(1, PublicId.generate());
                    update.setLong(2, id);
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
    }
}