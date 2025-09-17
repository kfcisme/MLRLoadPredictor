package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.util.List;

public class AutoScaler {
    private final List<Integer> cpuSteps; // e.g., [60,130,190,250] 對應 1..N 台
    public AutoScaler(List<Integer> cpuSteps){ this.cpuSteps = cpuSteps; }

    public int decideServers(double cpuHat){
        int n=1;
        for (int th : cpuSteps) if (cpuHat > th) n++;
        return n;
    }
}
