package me.wowkfccc.mlp;

import java.sql.ResultSet;
import java.util.*;

/**
 * 把一列 DB 記錄轉成 MLP ONNX 需要的 feature map。
 * - 優先讀 rate_* 欄位；
 * - 若沒有 rate_*，則用「事件次數 / active_minutes」動態換算；
 * - 並且自動產生 AFK_ratio 與 active_minutes。
 */
public class FeatureBuilder {

    // 依你的事件欄位維護這份清單（都是「原始次數欄名」）
    private static final String[] EVENT_NAMES = {
            "pickup","block_break","tnt_prime","multi_place","chat","block_damage","block_place",
            "craft","dmg_by_entity","death","explosion","furnace_extract","inv_close","inv_open",
            "bucket_empty","bucket_fill","cmd_pre","cmd_send","player_death","item_drop",
            "exp_change","interact","level_change","quit","respawn","teleport","chunkload","redstone"
    };

    // 你訓練時的特徵順序（建議與 best.pt 的 feat_cols 對齊；如有 dist_*、_cluster 也一起放）
    public static List<String> defaultOrder() {
        List<String> feats = new ArrayList<>();
        for (String raw : EVENT_NAMES) feats.add("rate_" + raw);
        feats.add("AFK_ratio");
        feats.add("active_minutes");
        return feats;
    }

    public static Map<String, Float> fromResultRow(ResultSet rs) throws Exception {
        Map<String, Float> m = new HashMap<>();

        // 1) 先算 AFK 相關與分母
        float afkSec = 0f;
        try { afkSec = rs.getFloat("afktime_sec"); } catch (Exception ignore){}
        float AFK_ratio = Math.max(0f, Math.min(1f, afkSec / 1800f));
        float active_minutes = Math.max(0f, 30f - afkSec / 60f);
        float denomMin = (active_minutes > 0f) ? active_minutes : 30f;

        m.put("AFK_ratio", AFK_ratio);
        m.put("active_minutes", active_minutes);

        // 2) rate_*：若表裡已有就直接用；否則以「次數/denom」換算
        for (String raw : EVENT_NAMES) {
            float v;
            boolean hasRate = false;
            try {
                v = rs.getFloat("rate_" + raw);
                if (!rs.wasNull()) {
                    m.put("rate_" + raw, v);
                    hasRate = true;
                }
            } catch (Exception ignore) {}

            if (!hasRate) {
                float cnt = 0f;
                try { cnt = rs.getFloat(raw); if (rs.wasNull()) cnt = 0f; } catch (Exception ignore) {}
                m.put("rate_" + raw, cnt / Math.max(denomMin, 1e-6f));
            }
        }

        return m;
    }
}
