package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.sql.*;
import java.util.*;

public class SchemaMigrator {

    // 事件欄位（原始次數），型別都用 INT，必要時你可改 BIGINT/DOUBLE
    private static final LinkedHashMap<String, String> EVENT_COLUMNS = new LinkedHashMap<>();
    static {
        EVENT_COLUMNS.put("pickup", "INT");
        EVENT_COLUMNS.put("block_break", "INT");
        EVENT_COLUMNS.put("tnt_prime", "INT");
        EVENT_COLUMNS.put("multi_place", "INT");
        EVENT_COLUMNS.put("chat", "INT");
        EVENT_COLUMNS.put("block_damage", "INT");
        EVENT_COLUMNS.put("block_place", "INT");
        EVENT_COLUMNS.put("craft", "INT");
        EVENT_COLUMNS.put("dmg_by_entity", "INT");
        EVENT_COLUMNS.put("death", "INT");
        EVENT_COLUMNS.put("explosion", "INT");
        EVENT_COLUMNS.put("furnace_extract", "INT");
        EVENT_COLUMNS.put("inv_close", "INT");
        EVENT_COLUMNS.put("inv_open", "INT");
        EVENT_COLUMNS.put("bucket_empty", "INT");
        EVENT_COLUMNS.put("bucket_fill", "INT");
        EVENT_COLUMNS.put("cmd_pre", "INT");
        EVENT_COLUMNS.put("cmd_send", "INT");
        EVENT_COLUMNS.put("player_death", "INT");
        EVENT_COLUMNS.put("item_drop", "INT");
        EVENT_COLUMNS.put("exp_change", "INT");
        EVENT_COLUMNS.put("interact", "INT");
        EVENT_COLUMNS.put("level_change", "INT");
        EVENT_COLUMNS.put("quit", "INT");
        EVENT_COLUMNS.put("respawn", "INT");
        EVENT_COLUMNS.put("teleport", "INT");
        EVENT_COLUMNS.put("chunkload", "INT");
        EVENT_COLUMNS.put("redstone", "INT");
    }

    public static void migrate(Db db, String dbName) throws Exception {
        try (Connection cn = db.conn()) {
            cn.setAutoCommit(true);

            // 1) player_events_30m（原始事件 + afktime）
            execUpdate(cn, """
                CREATE TABLE IF NOT EXISTS player_events_30m (
                  server_id   VARCHAR(64),
                  window_end  DATETIME,
                  player_id   VARCHAR(64),
                  afktime_sec INT,
                  PRIMARY KEY (server_id, window_end, player_id)
                ) ENGINE=InnoDB
            """);
            // 加缺的事件欄
            for (var e : EVENT_COLUMNS.entrySet()) {
                ensureColumn(cn, dbName, "player_events_30m", e.getKey(), e.getValue() + " DEFAULT 0");
            }

            // 2) player_type_pred
            execUpdate(cn, """
                CREATE TABLE IF NOT EXISTS player_type_pred (
                  server_id   VARCHAR(64),
                  window_end  DATETIME,
                  player_id   VARCHAR(64),
                  label       VARCHAR(32),
                  confidence  FLOAT,
                  PRIMARY KEY (server_id, window_end, player_id)
                ) ENGINE=InnoDB
            """);
            ensureColumn(cn, dbName, "player_type_pred", "label", "VARCHAR(32)");
            ensureColumn(cn, dbName, "player_type_pred", "confidence", "FLOAT");

            // 3) server_comp_30m
            execUpdate(cn, """
                CREATE TABLE IF NOT EXISTS server_comp_30m (
                  server_id      VARCHAR(64),
                  window_end     DATETIME,
                  total_players  INT,
                  p_AFK FLOAT, p_Build FLOAT, p_Explorer FLOAT, p_Explosive FLOAT,
                  p_PvP FLOAT, p_Redstone FLOAT, p_Social FLOAT, p_Survival FLOAT,
                  PRIMARY KEY (server_id, window_end)
                ) ENGINE=InnoDB
            """);
            // 確保各 p_* 存在
            for (String k : List.of("AFK","Build","Explorer","Explosive","PvP","Redstone","Social","Survival")) {
                ensureColumn(cn, dbName, "server_comp_30m", "p_" + k, "FLOAT");
            }
            ensureColumn(cn, dbName, "server_comp_30m", "total_players", "INT");

            // 4) server_load_30m（你的實際 CPU/RAM/Power ground truth）
            execUpdate(cn, """
                CREATE TABLE IF NOT EXISTS server_load_30m (
                  server_id  VARCHAR(64),
                  window_end DATETIME,
                  cpu   FLOAT,
                  ram   FLOAT,
                  power FLOAT,
                  PRIMARY KEY (server_id, window_end)
                ) ENGINE=InnoDB
            """);

            // 5) server_load_pred_30m（預測值）
            execUpdate(cn, """
                CREATE TABLE IF NOT EXISTS server_load_pred_30m (
                  server_id  VARCHAR(64),
                  window_end DATETIME,
                  cpu_hat   FLOAT,
                  ram_hat   FLOAT,
                  power_hat FLOAT,
                  source    VARCHAR(32),
                  PRIMARY KEY (server_id, window_end)
                ) ENGINE=InnoDB
            """);
            ensureColumn(cn, dbName, "server_load_pred_30m", "source", "VARCHAR(32)");
        }
    }

    private static boolean columnExists(Connection cn, String dbName, String table, String col) throws SQLException {
        String q = """
            SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=? LIMIT 1
        """;
        try (PreparedStatement ps = cn.prepareStatement(q)) {
            ps.setString(1, dbName);
            ps.setString(2, table);
            ps.setString(3, col);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureColumn(Connection cn, String dbName, String table, String col, String typeDef) throws SQLException {
        if (!columnExists(cn, dbName, table, col)) {
            String sql = "ALTER TABLE " + table + " ADD COLUMN " + col + " " + typeDef;
            execUpdate(cn, sql);
        }
    }

    private static void execUpdate(Connection cn, String sql) throws SQLException {
        try (Statement st = cn.createStatement()) {
            st.executeUpdate(sql);
        }
    }
}
