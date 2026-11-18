import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** SFTP 抖动：单通道 5 轮 / 单会话 10 通道 5 轮，同一文件同一区间。 */
public class SftpJitter2 {
    static Properties cfg;
    static final String PATH = "/share/videos/Akari25_3_8【実写LIVE】巨乳アイドルに甘々に耳舐めされる♡ASMR♡.mp4";

    static Session open() throws Exception {
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), "192.168.1.10", 2222);
        s.setPassword(cfg.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(8000);
        return s;
    }

    static long read(ChannelSftp c, long off, int len) {
        long got = 0;
        try (InputStream in = c.get(PATH, null, off)) {
            byte[] b = new byte[262144]; int k;
            while (got < len && (k = in.read(b, 0, (int) Math.min(b.length, len - got))) > 0) got += k;
        } catch (Exception e) { return -got; }
        return got;
    }

    static void rounds(String label, List<ChannelSftp> ch, int per, int rounds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, ch.size()));
        List<Long> ms = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            long t0 = System.currentTimeMillis();
            List<Future<Long>> fs = new ArrayList<>();
            for (int i = 0; i < ch.size(); i++) {
                final ChannelSftp c = ch.get(i);
                final long off = 256L * 1048576 + (long) r * 64L * 1048576 + (long) i * per;
                fs.add(pool.submit(() -> read(c, off, per)));
            }
            long bytes = 0;
            for (Future<Long> f : fs) { try { long v = f.get(120, TimeUnit.SECONDS); if (v > 0) bytes += v; } catch (Exception ignore) {} }
            long dt = Math.max(1, System.currentTimeMillis() - t0);
            ms.add(dt);
            System.out.printf("%s 轮%d: %d MiB / %d ms = %.2f MB/s%n", label, r, bytes / 1048576, dt, bytes / 1048576.0 / (dt / 1000.0));
        }
        long mn = Collections.min(ms), mx = Collections.max(ms);
        System.out.printf("%s 汇总: 抖动 %d ms（min %d / max %d）%n", label, mx - mn, mn, mx);
        pool.shutdownNow();
    }

    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }
        Session s = open();
        ChannelSftp c1 = (ChannelSftp) s.openChannel("sftp"); c1.connect(4000);
        rounds("单通道 16MiB", List.of(c1), 16 * 1024 * 1024, 5);
        c1.disconnect();
        List<ChannelSftp> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) { ChannelSftp c = (ChannelSftp) s.openChannel("sftp"); c.connect(4000); ten.add(c); }
        rounds("10 通道 x 4MiB", ten, 4 * 1024 * 1024, 5);
        for (ChannelSftp c : ten) c.disconnect();
        s.disconnect();
        System.out.println("SFTP_JITTER2_DONE");
    }
}
