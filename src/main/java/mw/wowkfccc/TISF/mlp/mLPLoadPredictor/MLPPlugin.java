package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.sql.*;
import java.time.*;
import java.util.*;

public class MLPPlugin extends JavaPlugin {
    private Db db;
    private MLPClassifier clf;
    private List<String> featOrder;
    private List<String> classes;
    private String serverId;
    private double unknownThr;
    private LoadRegressor reg;
    private AutoScaler scaler;
    private int windowMin;
    @Override
    public void onEnable(){
        saveDefaultConfig();
        var cfg = getConfig();

        serverId   = cfg.getString("server_id");
        unknownThr = cfg.getDouble("unknown_threshold", 0.55);
        windowMin  = cfg.getInt("window_minutes", 30);

        // 初始化 DB
        db = new Db(
                cfg.getString("mysql.url"),
                cfg.getString("mysql.username"),
                cfg.getString("mysql.password"),
                cfg.getInt("mysql.pool_size", 8)
        );

        try {
            // 初始化 MLP（feat 順序請與訓練一致）
            featOrder = me.wowkfccc.mlp.FeatureBuilder.defaultOrder();
            classes   = Arrays.asList("AFK","Build","Explorer","Explosive","PvP","Redstone","Social","Survival");
            clf = new MLPClassifier(cfg.getString("onnx_model_path"), featOrder);

            // 初始化多元回歸係數
            var beta = new HashMap<String, Double>();
            for (String k : classes) beta.put(k, cfg.getDouble("regression.beta."+k, 0.0));
            LoadRegressor.Coef co = new LoadRegressor.Coef();
            co.intercept = cfg.getDouble("regression.intercept", 5.0);
            co.beta      = beta;
            co.yLag1     = cfg.getDouble("regression.ar.y_lag1", 0.4);
            co.yLag2     = cfg.getDouble("regression.ar.y_lag2", 0.2);
            reg = new LoadRegressor(co, db);

            scaler = new AutoScaler(cfg.getIntegerList("autoscale.cpu_thresholds"));

            // 每 5 分鐘啟動一次異步排程
            long periodTicks = 5 * 60 * 20; // 5分鐘 * 60秒 * 20 tps
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::pipelineOnce, 200L, periodTicks);

            getLogger().info("MLPLoadPredictor enabled.");
        } catch (Exception e) {
            getLogger().severe("Init failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void pipelineOnce() {
        try {
            // 1) 取得當前對齊視窗尾端時間（例：00/30分）
            Timestamp windowEnd = currentAlignedWindowEnd();
            Timestamp windowEndPlus = new Timestamp(windowEnd.getTime() + windowMin * 60L * 1000L); // t+1 的時間

            // 2) 從原始事件表抓此視窗的所有玩家資料
            String q = "SELECT * FROM player_events_30m WHERE server_id=? AND window_end=?";
            List<String> labels = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            int rowCount = 0;

            try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(q)) {
                ps.setString(1, serverId);
                ps.setTimestamp(2, windowEnd);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // 2.1 將事件次數 → 特徵（rate_*、AFK_ratio、active_minutes）
                        Map<String, Float> fmap = me.wowkfccc.mlp.FeatureBuilder.fromResultRow(rs);

                        // 2.2 ONNX 分類
                        MLPClassifier.Out out = clf.predict(fmap, classes);

                        // 2.3 寫入玩家類型推論
                        String pid = safeStr(rs, "player_id");
                        insertPred(windowEnd, pid, out.label, max(out.probs));

                        labels.add(out.label);
                        confidences.add(max(out.probs));
                        rowCount++;
                    }
                }
            }
            if (rowCount == 0) {
                getLogger().info("[MLP] 視窗 " + windowEnd + " 無玩家資料，跳過。");
                return;
            }

            // 3) 聚合成類型分布（丟棄低信心）
            TypeAggregator.Comp comp = TypeAggregator.toComposition(labels, confidences, unknownThr);
            upsertComp(windowEnd, comp);

            // 4) 以迴歸估算「當前視窗」負載
            double cpuHatNow = reg.predictCpu(serverId, windowEnd, comp.p);
            upsertLoadPred(windowEnd, cpuHatNow, null, null, "regression");

            // 5) 可選：呼叫 LSTM 微服務，預測「下一視窗」的類型分布 → 估下一視窗負載
            if (getConfig().getBoolean("lstm.enabled", false)) {
                List<double[]> compSeq = fetchRecentCompSeq(windowEnd, 6); // 最近6個視窗
                if (compSeq.size() >= 2) {
                    List<Integer> nSeq = fetchRecentNSeq(windowEnd, 6);
                    double[] pNext = callCompForecast(compSeq, nSeq); // 8 維
                    if (pNext != null) {
                        Map<String, Double> pMap = new HashMap<>();
                        String[] keys = {"AFK","Build","Explorer","Explosive","PvP","Redstone","Social","Survival"};
                        double sum = 0.0;
                        for (double v : pNext) sum += v;
                        if (sum <= 0) sum = 1.0;
                        for (int i=0; i<8; i++) pMap.put(keys[i], Math.max(0.0, pNext[i]) / sum);

                        double cpuHatNext = reg.predictCpu(serverId, windowEnd, pMap);
                        // 你可以選擇把「下一窗」的預測，存到 t+1 的時間戳（較直觀）
                        upsertLoadPred(windowEndPlus, cpuHatNext, null, null, "lstm+reg");
                        getLogger().info(String.format("[MLP] %s → 估下一窗CPU=%.1f (source=lstm+reg)", windowEndPlus, cpuHatNext));
                    }
                }
            }

            // 6) 可選：自動擴縮
            if (getConfig().getBoolean("autoscale.enabled", false)) {
                int targetN = scaler.decideServers(cpuHatNow);
                getLogger().info("Predicted CPU(now)=" + String.format("%.1f", cpuHatNow) + " → target servers=" + targetN);
                // TODO: 連接你的實際啟/停服機制
            }

        } catch (Exception e) {
            getLogger().warning("pipelineOnce error: " + e.getMessage());
            e.printStackTrace();
        }
    }

//    private float max(float[] a){ float m=a[0]; for(int i=1;i<a.length;i++) if(a[i]>m) m=a[i]; return m; }

    private void insertPred(Timestamp we, String playerId, String label, float conf) throws Exception {
        String sql = "REPLACE INTO player_type_pred(server_id,window_end,player_id,label,confidence) VALUES(?,?,?,?,?)";
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setTimestamp(2, we);
            ps.setString(3, playerId);
            ps.setString(4, label);
            ps.setFloat(5, conf);
            ps.executeUpdate();
        }
    }

    private void upsertComp(Timestamp we, TypeAggregator.Comp c) throws Exception {
        String sql = """
        REPLACE INTO server_comp_30m(server_id,window_end,total_players,
          p_AFK,p_Build,p_Explorer,p_Explosive,p_PvP,p_Redstone,p_Social,p_Survival)
        VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """;
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setTimestamp(2, we);
            ps.setInt(3, c.total);
            ps.setDouble(4, c.p.getOrDefault("AFK", 0.0));
            ps.setDouble(5, c.p.getOrDefault("Build", 0.0));
            ps.setDouble(6, c.p.getOrDefault("Explorer", 0.0));
            ps.setDouble(7, c.p.getOrDefault("Explosive", 0.0));
            ps.setDouble(8, c.p.getOrDefault("PvP", 0.0));
            ps.setDouble(9, c.p.getOrDefault("Redstone", 0.0));
            ps.setDouble(10, c.p.getOrDefault("Social", 0.0));
            ps.setDouble(11, c.p.getOrDefault("Survival", 0.0));
            ps.executeUpdate();
        }
    }

    private void upsertLoadPred(Timestamp we, Double cpu, Double ram, Double power, String source) throws Exception {
        String sql = "REPLACE INTO server_load_pred_30m(server_id,window_end,cpu_hat,ram_hat,power_hat,source) VALUES(?,?,?,?,?,?)";
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setTimestamp(2, we);
            if (cpu == null) ps.setNull(3, java.sql.Types.FLOAT); else ps.setDouble(3, cpu);
            if (ram == null) ps.setNull(4, java.sql.Types.FLOAT); else ps.setDouble(4, ram);
            if (power == null) ps.setNull(5, java.sql.Types.FLOAT); else ps.setDouble(5, power);
            ps.setString(6, source);
            ps.executeUpdate();
        }
    }

    @Override
    public void onDisable(){
        try { if (clf!=null) clf.close(); } catch (Exception ignore){}
        if (db!=null) db.close();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args){
        if (label.equalsIgnoreCase("mlp-reload")){
            reloadConfig();
            s.sendMessage("§aMLP config reloaded.（下輪視窗會套用）");
            return true;
        }
        return false;
    }
    private Timestamp currentAlignedWindowEnd() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int minute = (now.getMinute() / windowMin) * windowMin;
        ZonedDateTime we = now.withMinute(minute).withSecond(0).withNano(0);
        return Timestamp.valueOf(we.toLocalDateTime());
    }
    private String parseDbNameFromUrl(String jdbcUrl){
        // 例: jdbc:mysql://127.0.0.1:3306/mc_analytics?xxx
        try {
            String afterHost = jdbcUrl.substring(jdbcUrl.indexOf("://")+3);
            String afterSlash = afterHost.substring(afterHost.indexOf("/")+1);
            String dbName = afterSlash.split("\\?")[0];
            return dbName;
        } catch (Exception e){
            getLogger().warning("無法從 JDBC URL 解析 DB 名稱，請在 config.yml 加 mysql.database");
            return "";
        }
    }
    private List<double[]> fetchRecentCompSeq(Timestamp we, int L) throws Exception {
        String sql = """
            SELECT p_AFK,p_Build,p_Explorer,p_Explosive,p_PvP,p_Redstone,p_Social,p_Survival
            FROM server_comp_30m
            WHERE server_id=? AND window_end<=?
            ORDER BY window_end DESC LIMIT ?
        """;
        List<double[]> seq = new ArrayList<>();
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setTimestamp(2, we);
            ps.setInt(3, L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] p = new double[8];
                    for (int i=0; i<8; i++) p[i] = rs.getDouble(i+1);
                    seq.add(p);
                }
            }
        }
        Collections.reverse(seq);
        return seq;
    }
    private List<Integer> fetchRecentNSeq(Timestamp we, int L) throws Exception {
        String sql = """
            SELECT total_players FROM server_comp_30m
            WHERE server_id=? AND window_end<=?
            ORDER BY window_end DESC LIMIT ?
        """;
        List<Integer> ns = new ArrayList<>();
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setTimestamp(2, we);
            ps.setInt(3, L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ns.add(rs.getInt(1));
            }
        }
        Collections.reverse(ns);
        return ns;
    }
    private double[] callCompForecast(List<double[]> compSeq, List<Integer> nSeq) throws Exception {
        var cfg = getConfig();
        if (!cfg.getBoolean("lstm.enabled", false)) return null;
        ApiClient api = new ApiClient(cfg.getString("lstm.base_url"), cfg.getInt("lstm.timeout_ms", 2000));

        // 手工組 JSON（也可用 Jackson）
        StringBuilder sb = new StringBuilder();
        sb.append("{\"server_id\":\"").append(serverId).append("\",\"comp_seq\":[");
        for (int i=0; i<compSeq.size(); i++) {
            double[] p = compSeq.get(i);
            sb.append("[");
            for (int j=0; j<p.length; j++) {
                if (j>0) sb.append(",");
                sb.append(String.format(java.util.Locale.US, "%.6f", p[j]));
            }
            sb.append("]");
            if (i<compSeq.size()-1) sb.append(",");
        }
        sb.append("],\"horizon\":1");
        if (nSeq != null && !nSeq.isEmpty()) {
            sb.append(",\"n_seq\":[");
            for (int i=0; i<nSeq.size(); i++) {
                if (i>0) sb.append(",");
                sb.append(nSeq.get(i));
            }
            sb.append("]");
        }
        sb.append("}");

        String res = api.postJson("/forecast_next_comp", sb.toString());
        // 超輕量解析：找到第一個 [ ... ] 當作 t+1 的分布
        int idx = res.indexOf("\"p_hat\"");
        if (idx < 0) return null;
        int lb = res.indexOf("[", idx);
        int rb = res.indexOf("]", lb);
        if (lb < 0 || rb < 0) return null;

        String arr = res.substring(lb+1, rb); // 第一個 step
        String[] parts = arr.replace("[","").replace("]","").split(",");
        if (parts.length < 8) return null;

        double[] out = new double[8];
        for (int i=0; i<8; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }
    private float max(float[] a){ float m=a[0]; for(int i=1;i<a.length;i++) if(a[i]>m) m=a[i]; return m; }
    private String safeStr(ResultSet rs, String col){ try { return rs.getString(col); } catch(Exception e){ return ""; } }
}

