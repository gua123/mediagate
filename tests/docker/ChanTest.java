import com.jcraft.jsch.*;
import java.io.*;
import java.util.*;

public class ChanTest {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(System.getenv().getOrDefault("CREDS", "/creds.properties"))) { p.load(in); }
        JSch jsch = new JSch();
        Session s = jsch.getSession(p.getProperty("user"), "192.168.1.10", 2222);
        s.setPassword(p.getProperty("password"));
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(10000);
        String path = "/share/videos/demo-sample.mp4";
        List<ChannelSftp> chans = new ArrayList<>();
        int max = 0;
        for (int i = 1; i <= 64; i++) {
            try {
                ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
                c.connect(5000);
                long t0 = System.currentTimeMillis();
                c.stat(path);
                long dt = System.currentTimeMillis() - t0;
                chans.add(c);
                max = i;
                if (i <= 4 || i % 8 == 0) System.out.println("通道 " + i + " OK, stat " + dt + "ms");
            } catch (Exception e) {
                System.out.println("通道 " + i + " 失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                break;
            }
        }
        System.out.println("MAX_CHANNELS=" + max);
        for (ChannelSftp c : chans) { try { c.disconnect(); } catch (Exception ignore) {} }
        s.disconnect();
        System.out.println("DONE");
    }
}
