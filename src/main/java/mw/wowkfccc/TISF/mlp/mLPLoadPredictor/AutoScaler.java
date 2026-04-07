package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.util.List;

public class AutoScaler {
    private final List<Integer> cpuSteps;
    public AutoScaler(List<Integer> cpuSteps){ this.cpuSteps = cpuSteps; }

    public int decideServers(double cpuHat){
        int n=1;
        for (int th : cpuSteps) if (cpuHat > th) n++;
        return n;
    }
}
