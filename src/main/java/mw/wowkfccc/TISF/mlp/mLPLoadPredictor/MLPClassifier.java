package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.*;

public class MLPClassifier implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> featOrder; // 與訓練時保存的 feat_cols 一致（建議固定順序）

    public MLPClassifier(String onnxPath, List<String> featOrder) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        SessionOptions so = new SessionOptions();
        // 若機器支援 CUDA 可在此開啟：so.addCUDA(0);
        this.session = env.createSession(onnxPath, so);
        this.featOrder = featOrder;
    }

    public static class Out {
        public final String label;
        public final float[] probs;
        public Out(String label, float[] probs){ this.label = label; this.probs = probs; }
    }

    public Out predict(Map<String, Float> featureMap, List<String> classes) throws Exception {
        int d = featOrder.size();
        float[] x = new float[d];
        for (int i=0;i<d;i++) x[i] = featureMap.getOrDefault(featOrder.get(i), 0f);

        long[] shape = new long[]{1, d};
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape)) {
            OrtSession.Result res = session.run(Collections.singletonMap("features", input));
            float[][] logits = (float[][]) res.get(0).getValue();
            float[] p = softmax(logits[0]);
            int arg = argmax(p);
            return new Out(classes.get(arg), p);
        }
    }

    private static int argmax(float[] a){ int m=0; for(int i=1;i<a.length;i++) if(a[i]>a[m]) m=i; return m; }
    private static float[] softmax(float[] z){
        float max = Float.NEGATIVE_INFINITY;
        for (float v : z) max = Math.max(max, v);
        double sum = 0;
        double[] e = new double[z.length];
        for (int i=0;i<z.length;i++){ e[i]=Math.exp(z[i]-max); sum+=e[i]; }
        float[] p = new float[z.length];
        for (int i=0;i<z.length;i++) p[i]=(float)(e[i]/sum);
        return p;
    }

    @Override public void close() throws Exception { session.close(); env.close(); }
}
