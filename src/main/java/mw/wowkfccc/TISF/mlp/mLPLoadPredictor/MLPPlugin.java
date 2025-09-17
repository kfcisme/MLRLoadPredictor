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

        serverId = cfg.getString("server_id");
        unknownThr = cfg.getDouble("unknown_threshold", 0.55);
        windowMin = cfg.getInt("window_minutes", 30);

        // DB
        db = new Db(
                cfg.getString("mysql.url"),
                cfg.getString("mysql.username"),
                cfg.getString("mysql.password"),
                cfg.getInt("mysql.pool_size",8));

        try {
            // 建議：把 feat_cols / classes 存成 JSON/檔案；此處示範用 FeatureBuilder 預設 + 手填 classes
            featOrder = FeatureBuilder.defaultOrder();
            classes = Arrays.asList("AFK","Build","Explorer","Explosive","PvP","Redstone","Social","Survival");

            clf = new MLPClassifier(cfg.getString("onnx_model_path"), featOrder);

            // 讀回歸係數
            var beta = new HashMap<String, Double>();
            for (String k : classes) beta.put(k, cfg.getDouble("regression.beta."+k, 0.0));
            LoadRegressor.Coef co = new LoadRegressor.Coef();
            co.intercept = cfg.getDouble("regression.intercept", 5.0);
            co.beta = beta;
            co.yLag1 = cfg.getDouble("regression.ar.y_lag1", 0.4);
            co.yLag2 = cfg.getDouble("regression.ar.y_lag2", 0.2);
            reg = new LoadRegressor(co, db);

            // 擴縮器
            scaler = new AutoScaler(cfg.getIntegerList("autoscale.cpu_thresholds"));

            // 每 5 分鐘跑一次，檢查是否到視窗尾巴
            long periodTicks = 5 * 60 * 20; // 5 min
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::pipelineOnce, 200L, periodTicks);

            getLogger().info("MLPLoadPredictor enabled.");

        } catch (Exception e){
            getLogger().severe("Init failed: "+e.getMessage());
            e.printStackTrace();
        }
    }

    private void pipelineOnce(){
        try {
            // 決定這一輪要處理的 window_end（對齊整點/半點）
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            int minute = (now.getMinute()/windowMin)*windowMin;
            ZonedDateTime we = now.withMinute(minute).withSecond(0).withNano(0);
            // 為避免資料未寫完，可延遲一個小窗口
            we = we.minusMinutes(0);

            java.sql.Timestamp windowEnd = java.sql.Timestamp.valueOf(we.toLocalDateTime());

            // 1) 從 DB 抓該視窗所有玩家的 events（你既有系統會落到 player_events_30m）
            String sql = "SELECT * FROM player_events_30m WHERE server_id=? AND window_end=?";
            List<String> labels = new ArrayList<>();
            List<Float> confs = new ArrayList<>();
            try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)){
                ps.setString(1, serverId);
                ps.setTimestamp(2, windowEnd);
                ResultSet rs = ps.executeQuery();
                int n=0;
                while (rs.next()){
                    var fmap = FeatureBuilder.fromResultRow(rs);
                    var out  = clf.predict(fmap, classes);
                    // 寫入 player_type_pred
                    insertPred(windowEnd, rs.getString("player_id"), out.label, max(out.probs));
                    labels.add(out.label);
                    confs.add(max(out.probs));
                    n++;
                }
                if (n==0) return; // 無資料則跳過
            }

            // 2) 聚合成類型分布 p_t
            var comp = TypeAggregator.toComposition(labels, confs, unknownThr);
            upsertComp(windowEnd, comp);

            // 3) 以回歸估算 CPU/ RAM/ Power（示例：CPU）
            double cpuHat = reg.predictCpu(serverId, windowEnd, comp.p);
            upsertLoadPred(windowEnd, cpuHat, null, null, "regression");

            // 4) 擴縮（可選）
            if (getConfig().getBoolean("autoscale.enabled", false)) {
                int targetN = scaler.decideServers(cpuHat);
                // TODO: 你在這裡呼叫實際啟/停服腳本 or API（例：systemd/docker/k8s/自寫 Bash）
                getLogger().info("Predicted CPU="+String.format("%.1f", cpuHat)+" → target servers="+targetN);
            }

        } catch (Exception e){
            getLogger().warning("pipelineOnce error: "+e.getMessage());
        }
    }

    private float max(float[] a){ float m=a[0]; for(int i=1;i<a.length;i++) if(a[i]>m) m=a[i]; return m; }

    private void insertPred(Timestamp we, String playerId, String label, float conf) throws Exception {
        String sql = "REPLACE INTO player_type_pred(server_id,window_end,player_id,label,confidence) VALUES(?,?,?,?,?)";
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)){
            ps.setString(1, serverId); ps.setTimestamp(2, we); ps.setString(3, playerId);
            ps.setString(4, label); ps.setFloat(5, conf);
            ps.executeUpdate();
        }
    }

    private void upsertComp(Timestamp we, TypeAggregator.Comp c) throws Exception {
        // 先查總玩家數（可用 player_type_pred 也可用 events）
        int total = c.total;
        String sql = """
        REPLACE INTO server_comp_30m(server_id,window_end,total_players,
          p_AFK,p_Build,p_Explorer,p_Explosive,p_PvP,p_Redstone,p_Social,p_Survival)
        VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """;
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)){
            ps.setString(1, serverId); ps.setTimestamp(2, we); ps.setInt(3, total);
            ps.setDouble(4, c.p.getOrDefault("AFK",0.0));
            ps.setDouble(5, c.p.getOrDefault("Build",0.0));
            ps.setDouble(6, c.p.getOrDefault("Explorer",0.0));
            ps.setDouble(7, c.p.getOrDefault("Explosive",0.0));
            ps.setDouble(8, c.p.getOrDefault("PvP",0.0));
            ps.setDouble(9, c.p.getOrDefault("Redstone",0.0));
            ps.setDouble(10, c.p.getOrDefault("Social",0.0));
            ps.setDouble(11, c.p.getOrDefault("Survival",0.0));
            ps.executeUpdate();
        }
    }

    private void upsertLoadPred(Timestamp we, Double cpu, Double ram, Double power, String src) throws Exception {
        String sql = "REPLACE INTO server_load_pred_30m(server_id,window_end,cpu_hat,ram_hat,power_hat,source) VALUES(?,?,?,?,?,?)";
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)){
            ps.setString(1, serverId); ps.setTimestamp(2, we);
            if (cpu==null) ps.setNull(3, java.sql.Types.FLOAT); else ps.setDouble(3, cpu);
            if (ram==null) ps.setNull(4, java.sql.Types.FLOAT); else ps.setDouble(4, ram);
            if (power==null) ps.setNull(5, java.sql.Types.FLOAT); else ps.setDouble(5, power);
            ps.setString(6, src);
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
}

