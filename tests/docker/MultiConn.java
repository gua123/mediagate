import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MultiConn {
    static Properties cfg;
    static Session open() throws Exception {
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), "192.168.1.10", 2222);
        s.setPassword(cfg.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(8000);
        return s;
    }
    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }
        String path = "/share/videos/demo-sample.mp4";

        // 逐条建立 16 条会话（避免 MaxStartups 限流），每条开 10 通道
        List<Session> sessions = new ArrayList<>();
        List<ChannelSftp> chans = new ArrayList<>();
        int ok = 0;
        for (int i = 1; i <= 16; i++) {
            try {
                Session s = open();
                sessions.add(s);
                int n = 0;
                for (int k = 0; k < 10; k++) {
                    try {
                        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
                        c.connect(4000);
                        chans.add(c); n++;
                    } catch (Exception e) { break; }
                }
                ok++;
                if (i <= 2 || i % 4 == 0) System.out.println("会话 " + i + " 通道 " + n);
            } catch (Exception e) {
                System.out.println("会话 " + i + " 建连失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                break;
            }
        }
        System.out.println("CONNECTIONS=" + ok + " CHANNELS=" + chans.size());

        // 吞吐测试：所有通道并发读 256KB，测总耗时
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, chans.size()));
        List<Future<Integer>> fs = new ArrayList<>();
        for (ChannelSftp c : chans) {
            fs.add(pool.submit(() -> {
                try {
                    InputStream in = c.get(path, null, 0L);
                    byte[] b = new byte[256 * 1024];
                    int total = 0, k;
                    while (total < b.length && (k = in.read(b, total, b.length - total)) > 0) total += k;
                    in.close();
                    return total;
                } catch (Exception e) { return 0; }
            }));
        }
        long bytes = 0;
        for (Future<Integer> f : fs) { try { bytes += f.get(30, TimeUnit.SECONDS); } catch (Exception ignore) {} }
        long dt = Math.max(1, System.currentTimeMillis() - t0);
        System.out.println("并发读 " + bytes + " 字节 / " + dt + " ms = " + (bytes * 1000 / dt / 1024 / 1024) + " MB/s（总）");
        pool.shutdownNow();
        for (ChannelSftp c : chans) { try { c.disconnect(); } catch (Exception ignore) {} }
        for (Session s : sessions) { try { s.disconnect(); } catch (Exception ignore) {} }
        System.out.println("DONE");
    }
}
