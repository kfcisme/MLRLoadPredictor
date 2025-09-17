package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

public class LoadRegressor {
    public static class Coef {
        public double intercept;
        public Map<String, Double> beta; // 類型係數
        public double yLag1, yLag2;
    }

    private final Coef c;
    private final Db db;

    public LoadRegressor(Coef c, Db db){ this.c=c; this.db=db; }

    public double predictCpu(String serverId, java.sql.Timestamp windowEnd, Map<String, Double> p){
        double y = c.intercept;
        for (var e : c.beta.entrySet()){
            y += e.getValue() * p.getOrDefault(e.getKey(), 0.0);
        }
        double lag1 = getLag(serverId, windowEnd, 1);
        double lag2 = getLag(serverId, windowEnd, 2);
        y += c.yLag1 * lag1 + c.yLag2 * lag2;
        return Math.max(0.0, y);
    }

    private double getLag(String serverId, java.sql.Timestamp windowEnd, int k){
        String sql = "SELECT cpu FROM server_load_30m WHERE server_id=? AND window_end=DATE_SUB(?, INTERVAL ? MINUTE)";
        try (Connection cn = db.conn(); PreparedStatement ps = cn.prepareStatement(sql)){
            ps.setString(1, serverId);
            ps.setTimestamp(2, windowEnd);
            ps.setInt(3, k * 30);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble(1);
        } catch (Exception ignore){}
        return 0.0;
    }
}
