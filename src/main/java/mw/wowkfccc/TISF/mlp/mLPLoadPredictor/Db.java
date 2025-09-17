package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;

public class Db {
    private final HikariDataSource ds;
    public Db(String url, String user, String pass, int pool) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(pool);
        this.ds = new HikariDataSource(cfg);
    }
    public Connection conn() throws SQLException { return ds.getConnection(); }
    public void close(){ ds.close(); }
}
