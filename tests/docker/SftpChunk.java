import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;

/** 单通道块大小敏感性：同一文件、同一通道，用不同 read 缓冲大小量吞吐。 */
public class SftpChunk {
    static Properties cfg;
    static final String PATH = "/share/videos/demo-sample.mp4";

    public static void main(String[] a) throws Exception {
        cfg = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { cfg.load(in); }
        JSch j = new JSch();
        Session s = j.getSession(cfg.getProperty("user"), "192.168.1.10", 2222);
        s.setPassword(cfg.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(8000);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect(4000);
        for (int bs : new int[]{4096, 32768, 262144, 1048576}) {
            long want = 16L * 1024 * 1024;
            long t0 = System.currentTimeMillis();
            InputStream in = c.get(PATH, null, 0L);
            byte[] b = new byte[bs];
            long got = 0; int k;
            while (got < want && (k = in.read(b, 0, (int) Math.min(bs, want - got))) > 0) got += k;
            in.close();
            long ms = Math.max(1, System.currentTimeMillis() - t0);
            System.out.printf("read 缓冲 %6d B: %5d MiB / %5d ms = %6.2f MB/s%n", bs, got / 1048576, ms, got / 1048576.0 / (ms / 1000.0));
        }
        c.disconnect(); s.disconnect();
        System.out.println("SFTP_CHUNK_DONE");
    }
}
