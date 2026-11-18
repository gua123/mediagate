import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** SFTP 对照基线（同文件、同机器、同期）：单通道 / 单会话 10 通道 / 20 会话 x 10 通道。 */
public class SftpBaseline {
    static Properties cfg;
    static final String HOST = "192.168.1.10";
    static final int PORT = 2222;
    static final String PATH = "/share/videos/Akari25_3_8【実写LIVE】巨乳アイドルに甘々に耳舐めされる♡ASMR♡.mp4";

    static Session open() throws Exception {
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), HOST, PORT);
        s.setPassword(cfg.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(8000);
        return s;
    }

    static ChannelSftp chan(Session s) throws Exception {
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect(4000);
        return c;
    }

    static long read(ChannelSftp c, long off, int len) {
        try {
            InputStream in = c.get(PATH, null, off);
            byte[] b = new byte[256 * 1024];
            long total = 0; int k;
            while (total < len && (k = in.read(b, 0, (int) Math.min(b.length, len - total))) > 0) total += k;
            in.close();
            return total;
        } catch (Exception e) {
            System.out.println("  read fail @" + off + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            return 0;
        }
    }

    static void stage(String label, List<ChannelSftp> ch, int per, long baseOff) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, ch.size()));
        long t0 = System.currentTimeMillis();
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < ch.size(); i++) {
            final ChannelSftp c = ch.get(i);
            final long off = baseOff + (long) i * per;
            fs.add(pool.submit(() -> read(c, off, per)));
        }
        long bytes = 0;
        for (Future<Long> f : fs) { try { bytes += f.get(120, TimeUnit.SECONDS); } catch (Exception ignore) {} }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        System.out.printf("%s: %d 通道, %d MiB / %d ms = %.2f MB/s%n",
                label, ch.size(), bytes / 1048576, ms, bytes / 1048576.0 / (ms / 1000.0));
        pool.shutdownNow();
    }

    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }
        if (a.length > 0 && a[0].equals("quick")) {
            Session s = open();
            ChannelSftp c = chan(s);
            stage("单会话 单通道（32MiB）", List.of(c), 32 * 1024 * 1024, 0);
            c.disconnect(); s.disconnect();
            return;
        }
        // 1) 单会话单通道
        Session s1 = open(); ChannelSftp c1 = chan(s1);
        stage("单会话 单通道（32MiB）", List.of(c1), 32 * 1024 * 1024, 0);
        // 2) 单会话 10 通道
        List<ChannelSftp> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(chan(s1));
        stage("单会话 10 通道（各 4MiB）", ten, 4 * 1024 * 1024, 64L * 1048576);
        for (ChannelSftp c : ten) c.disconnect();
        c1.disconnect(); s1.disconnect();

        // 3) 20 会话 x 10 通道
        List<Session> ss = new ArrayList<>();
        List<ChannelSftp> all = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < 20; i++) {
                Session s = open(); ss.add(s);
                for (int k = 0; k < 10; k++) all.add(chan(s));
            }
        } catch (Exception e) {
            System.out.println("建连中断于 " + ss.size() + " 会话: " + e.getMessage());
        }
        System.out.println("建连 " + ss.size() + " 会话 / " + all.size() + " 通道，用时 " + (System.currentTimeMillis() - t0) + " ms");
        stage("20 会话 x 10 通道（各 2MiB）", all, 2 * 1024 * 1024, 128L * 1048576);
        for (ChannelSftp c : all) { try { c.disconnect(); } catch (Exception ignore) {} }
        for (Session s : ss) { try { s.disconnect(); } catch (Exception ignore) {} }
        System.out.println("SFTP_BASELINE_DONE");
    }
}
