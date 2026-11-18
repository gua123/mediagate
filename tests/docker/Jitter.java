import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Jitter {
    static Properties cfg;
    static Session open(String host, int port) throws Exception {
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), host, port);
        s.setPassword(cfg.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(8000);
        return s;
    }
    static void run(String host, int port, String label, int conns, int rounds) {
        System.out.println("=== " + label + " (" + host + ":" + port + ") ===");
        List<Session> ss = new ArrayList<>();
        List<ChannelSftp> ch = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 1; i <= conns; i++) {
                Session s = open(host, port);
                ss.add(s);
                for (int k = 0; k < 10; k++) {
                    ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
                    c.connect(4000);
                    ch.add(c);
                }
            }
        } catch (Exception e) {
            System.out.println("建连失败于 " + ss.size() + " 条会话: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        System.out.println("建立 " + ss.size() + " 会话 / " + ch.size() + " 通道，用时 " + (System.currentTimeMillis()-t0) + " ms");
        if (ch.isEmpty()) { System.out.println("无可用通道，跳过"); return; }

        String path = "/share/videos/demo-sample.mp4";
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(ch.size(), 64));
        List<Long> totals = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            long rt0 = System.currentTimeMillis();
            List<Future<Integer>> fs = new ArrayList<>();
            for (ChannelSftp c : ch) {
                fs.add(pool.submit(() -> {
                    try {
                        InputStream in = c.get(path, null, 0L);
                        byte[] b = new byte[256*1024];
                        int total = 0, k;
                        while (total < b.length && (k = in.read(b, total, b.length-total)) > 0) total += k;
                        in.close();
                        return total;
                    } catch (Exception e) { return 0; }
                }));
            }
            long bytes = 0; int fails = 0;
            for (Future<Integer> f : fs) {
                try { int v = f.get(60, TimeUnit.SECONDS); bytes += v; if (v == 0) fails++; }
                catch (Exception e) { fails++; }
            }
            long dt = Math.max(1, System.currentTimeMillis() - rt0);
            long mbps = bytes * 1000 / dt / 1024 / 1024;
            totals.add(dt);
            System.out.println("轮 " + r + ": " + bytes/1024/1024 + " MB / " + dt + " ms = " + mbps + " MB/s, 失败 " + fails);
            try { for (ChannelSftp c : ch) { /* 保持 */ } } catch (Exception ignore) {}
        }
        if (!totals.isEmpty()) {
            long min = Collections.min(totals), max = Collections.max(totals);
            double avg = totals.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.println("耗时分部: min=" + min + "ms avg=" + (long)avg + "ms max=" + max + "ms  抖动=" + (max-min) + "ms");
        }
        pool.shutdownNow();
        for (ChannelSftp c : ch) { try { c.disconnect(); } catch (Exception ignore) {} }
        for (Session s : ss) { try { s.disconnect(); } catch (Exception ignore) {} }
    }
    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }
        run("192.168.1.10", 2222, "内网 20 连接", 20, 4);
        run("nas.example.com", 2222, "公网 20 连接", 20, 4);
        System.out.println("ALL_DONE");
    }
}
