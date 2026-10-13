import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Base64;

/** 端到端: JVM 生成签名 → 真实请求抖音 API → 验证拿到视频 */
public class E2ETest {
    static final String CHARACTER = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=";
    static final byte[] UA_KEY = new byte[]{0x00, 0x01, 0x0c};
    static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    static final int[] HEX_TABLE = new int[128];
    static {
        java.util.Arrays.fill(HEX_TABLE, -1);
        String hex = "0123456789abcdef";
        for (int i = 0; i < hex.length(); i++) HEX_TABLE[hex.charAt(i)] = i;
    }
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    // ★ 安全：真实登录 Cookie 已删除（曾经硬编码在此，等于把账号凭证写进仓库）。
    //   需要跑端到端验证时，临时用自己浏览器导出的 Cookie 替换下面这行。
    static final String COOKIE = "REPLACE_WITH_YOUR_OWN_COOKIE";

    public static void main(String[] args) throws Exception {
        String params = buildParams(0, 10);
        String xb = generate(params, UA);
        String url = "https://www.douyin.com/aweme/v1/web/tab/feed/?" + params + "&X-Bogus=" + xb;

        System.out.println("X-Bogus: " + xb);
        System.out.println("请求中...");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "https://www.douyin.com/");
        conn.setRequestProperty("Cookie", COOKIE);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);

        int code = conn.getResponseCode();
        System.out.println("HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        String body = sb.toString();
        System.out.println("响应长度: " + body.length() + " bytes");

        if (body.length() > 0) {
            // 粗略解析
            boolean hasStatus0 = body.contains("\"status_code\":0");
            int awemeIdx = body.indexOf("\"aweme_list\"");
            System.out.println("status_code:0 → " + hasStatus0);
            if (awemeIdx > 0) {
                String seg = body.substring(awemeIdx, Math.min(awemeIdx + 800, body.length()));
                int urlIdx = seg.indexOf("\"url_list\"");
                if (urlIdx > 0) {
                    String urlSeg = seg.substring(urlIdx, Math.min(urlIdx + 300, seg.length()));
                    System.out.println("找到 url_list ✓");
                    System.out.println(urlSeg.substring(0, Math.min(200, urlSeg.length())));
                }
            }
            System.out.println();
            System.out.println(">>> 端到端成功: 拿到真实数据 <<<");
        } else {
            System.out.println(">>> 失败: 空响应 <<<");
        }
    }

    static String buildParams(long maxCursor, int count) {
        return "device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=0"
            + "&max_cursor=" + maxCursor + "&locate_query=false&show_live_replay_strategy=1"
            + "&need_time_list=1&time_list_query=0&whale_cut_token=&cut_version=1&count=" + count
            + "&publish_video_strategy_type=2&pc_client_type=1&version_code=170400&version_name=17.4.0"
            + "&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN"
            + "&browser_platform=Win32&browser_name=Chrome&browser_version=122.0.0.0&os_name=Windows"
            + "&os_version=10&cpu_core_num=8&device_memory=8&platform=PC&downlink=10"
            + "&effective_type=4g&round_trip_time=50";
    }

    static String generate(String urlParams, String userAgent) {
        int timer = (int) (System.currentTimeMillis() / 1000);
        byte[] rc4Ua = rc4Encrypt(UA_KEY, userAgent.getBytes(LATIN1));
        String b64Ua = Base64.getEncoder().encodeToString(rc4Ua);
        int[] array1 = md5StrToArray(md5Hex(b64Ua));
        int[] array2 = md5StrToArray(md5Hex(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")));
        int[] urlParamsArray = md5Encrypt(urlParams);
        int ct = 536919696;
        int[] newArray = new int[]{
            64, 0, 1, 12, urlParamsArray[14], urlParamsArray[15],
            array2[14], array2[15], array1[14], array1[15],
            (timer >>> 24) & 255, (timer >>> 16) & 255, (timer >>> 8) & 255, timer & 255,
            (ct >>> 24) & 255, (ct >>> 16) & 255, (ct >>> 8) & 255, ct & 255
        };
        int xorResult = newArray[0];
        for (int i = 1; i < newArray.length; i++) xorResult ^= newArray[i];
        int[] y = new int[newArray.length + 1];
        System.arraycopy(newArray, 0, y, 0, newArray.length);
        y[y.length - 1] = xorResult;
        byte[] yBytes = new byte[y.length];
        for (int i = 0; i < y.length; i++) yBytes[i] = (byte) y[i];
        String encoded = new String(yBytes, LATIN1);
        byte[] rc4Result = rc4Encrypt(new byte[]{(byte) 0xFF}, encoded.getBytes(LATIN1));
        byte[] garbled = new byte[rc4Result.length + 2];
        garbled[0] = 2; garbled[1] = (byte) 0xFF;
        System.arraycopy(rc4Result, 0, garbled, 2, rc4Result.length);
        String garbledStr = new String(garbled, LATIN1);
        StringBuilder sb = new StringBuilder();
        char[] chars = garbledStr.toCharArray();
        int ci = 0;
        while (ci + 2 < chars.length) { sb.append(calculation(chars[ci] & 255, chars[ci+1] & 255, chars[ci+2] & 255)); ci += 3; }
        return sb.toString();
    }

    static int[] md5StrToArray(String s) {
        if (s.length() > 32) { int[] r = new int[s.length()]; for (int i = 0; i < s.length(); i++) r[i] = s.charAt(i); return r; }
        int[] out = new int[s.length() / 2]; int idx = 0, oi = 0;
        while (idx + 1 < s.length()) { out[oi++] = ((HEX_TABLE[s.charAt(idx)] << 4) | HEX_TABLE[s.charAt(idx+1)]) & 0xFF; idx += 2; }
        return out;
    }
    static int[] md5Encrypt(String p) { return md5StrToArray(md5Hex(md5StrToArray(md5Hex(p)))); }
    static String md5Hex(String in) {
        try { byte[] d = MessageDigest.getInstance("MD5").digest(in.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b & 0xFF)); return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String md5Hex(int[] in) {
        byte[] bytes = new byte[in.length]; for (int i = 0; i < in.length; i++) bytes[i] = (byte) in[i];
        try { byte[] d = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b & 0xFF)); return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static byte[] rc4Encrypt(byte[] key, byte[] data) {
        int[] s = new int[256]; for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) { j = (j + s[i] + (key[i % key.length] & 0xFF)) % 256; int t = s[i]; s[i] = s[j]; s[j] = t; }
        byte[] out = new byte[data.length]; int x = 0, yy = 0;
        for (int k = 0; k < data.length; k++) { x = (x + 1) % 256; yy = (yy + s[x]) % 256; int t = s[x]; s[x] = s[yy]; s[yy] = t;
            out[k] = (byte) ((data[k] & 0xFF) ^ s[(s[x] + s[yy]) % 256]); }
        return out;
    }
    static String calculation(int a1, int a2, int a3) {
        int x3 = ((a1 & 255) << 16) | ((a2 & 255) << 8) | (a3 & 255);
        return "" + CHARACTER.charAt((x3 & 16515072) >> 18) + CHARACTER.charAt((x3 & 258048) >> 12)
            + CHARACTER.charAt((x3 & 4032) >> 6) + CHARACTER.charAt(x3 & 63);
    }
}
