import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** SFTP 多连接/多通道吞吐（同文件、固定 8MiB/通道，避免小 get 的开销污染）。 */
public class SftpMulti2 {
    static Properties cfg;
    static final String PATH = "/share/videos/demo-sample.mp4";
    static final int PER = 8 * 1024 * 1024;

    static Session open() throws Exception {
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), "192.168.1.10", 2222);
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

    static void stage(String label, List<ChannelSftp> ch, long baseOff) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, ch.size()));
        long t0 = System.currentTimeMillis();
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < ch.size(); i++) {
            final ChannelSftp c = ch.get(i);
            final long off = baseOff + (long) i * PER;
            fs.add(pool.submit(() -> {
                long got = 0;
                try (InputStream in = c.get(PATH, null, off)) {
                    byte[] b = new byte[262144]; int k;
                    while (got < PER && (k = in.read(b, 0, (int) Math.min(b.length, PER - got))) > 0) got += k;
                } catch (Exception e) { return -got; }
                return got;
            }));
        }
        long bytes = 0; int fails = 0;
        for (Future<Long> f : fs) { try { long v = f.get(180, TimeUnit.SECONDS); if (v < 0) { fails++; } else bytes += v; } catch (Exception e) { fails++; } }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        System.out.printf("%s: 通道 %d, %d MiB / %d ms = %.2f MB/s, 失败 %d%n",
                label, ch.size(), bytes / 1048576, ms, bytes / 1048576.0 / (ms / 1000.0), fails);
        pool.shutdownNow();
    }

    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }

        Session s1 = open();
        List<ChannelSftp> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(chan(s1));
        System.out.println("单会话通道数 = " + ten.size());
        stage("单会话 10 通道", ten, 0);
        for (ChannelSftp c : ten) c.disconnect();
        s1.disconnect();

        List<Session> ss = new ArrayList<>();
        List<ChannelSftp> all = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            try {
                Session s = open(); ss.add(s);
                for (int k = 0; k < 10; k++) all.add(chan(s));
            } catch (Exception e) { System.out.println("建连中断 @" + i + ": " + e.getMessage()); break; }
        }
        System.out.println("建连 " + ss.size() + " 会话 / " + all.size() + " 通道，用时 " + (System.currentTimeMillis() - t0) + " ms");
        stage("20 会话 x 10 通道", all, 128L * 1048576);
        for (ChannelSftp c : all) { try { c.disconnect(); } catch (Exception ignore) {} }
        for (Session s : ss) { try { s.disconnect(); } catch (Exception ignore) {} }
        System.out.println("SFTP_MULTI2_DONE");
    }
}
