package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.util.*;

public class TypeAggregator {
    public static class Comp {
        public final Map<String, Integer> count = new HashMap<>();
        public final Map<String, Double> p = new HashMap<>();
        public int total = 0;
    }

    public static Comp toComposition(List<String> labels, List<Float> confidences, double unknownThreshold){
        Comp c = new Comp();
        for (int i=0;i<labels.size();i++){
            String lab = labels.get(i);
            float conf = confidences.get(i);
            if (conf < unknownThreshold) continue;
            c.count.merge(lab, 1, Integer::sum);
            c.total++;
        }
        for (var e : c.count.entrySet()){
            c.p.put(e.getKey(), e.getValue() / Math.max(1.0, (double)c.total));
        }
        return c;
    }
}
