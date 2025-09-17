package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.sql.ResultSet;
import java.util.*;

public class FeatureBuilder {
    // 依你的 samples / rate_* 欄位調整
    public static List<String> defaultOrder() {
        return List.of(
                "rate_pickup","rate_block_break","rate_tnt_prime","rate_multi_place","rate_chat",
                "rate_block_damage","rate_block_place","rate_craft","rate_dmg_by_entity","rate_death",
                "rate_explosion","rate_furnace_extract","rate_inv_close","rate_inv_open","rate_bucket_empty",
                "rate_bucket_fill","rate_cmd_pre","rate_cmd_send","rate_player_death","rate_item_drop",
                "rate_exp_change","rate_interact","rate_level_change","rate_quit","rate_respawn",
                "rate_teleport","rate_chunkload","rate_redstone"
                // 如有 dist_* / _cluster / pmax 一併加入，順序要固定
        );
    }

    public static Map<String, Float> fromResultRow(ResultSet rs) throws Exception {
        Map<String, Float> m = new HashMap<>();
        for (String f : defaultOrder()) {
            float v = 0f;
            try { v = rs.getFloat(f); if (rs.wasNull()) v=0f; } catch (Exception ignore){}
            m.put(f, v);
        }
        // 如要加 AFK 比例：
        try {
            int afk = rs.getInt("afktime_sec");
            m.put("AFK_ratio", Math.min(1f, Math.max(0f, afk / 1800f)));
            m.put("active_minutes", Math.max(0f, 30f - afk / 60f));
        } catch (Exception ignore){}
        return m;
    }
}
