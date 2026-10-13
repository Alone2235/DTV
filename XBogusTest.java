import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * JVM 版 XBogus 测试 —— 验证 Kotlin 实现的逻辑正确性
 * 固定 timer 对比 Python 官方 f2 输出:
 *   timer=1789144081 → DFSzswVYFu0ANxu0CvpYlF9WX7JD
 */
public class XBogusTest {

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
    static final String PARAMS = "device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=0&max_cursor=0&count=10";

    public static void main(String[] args) {
        int[] timers = {1789144081, 1700000000, 1234567890};
        String[] expected = {
            "DFSzswVYFu0ANxu0CvpYlF9WX7JD",
            "DFSzswVYFu0ANxu0tmWx-e9WX7Jy",
            "DFSzswVYFu0ANxu097B4Fl9WX7nS"
        };

        boolean allMatch = true;
        for (int i = 0; i < timers.length; i++) {
            String got = generate(PARAMS, UA, timers[i]);
            boolean match = got.equals(expected[i]);
            if (!match) allMatch = false;
            System.out.println("timer=" + timers[i]);
            System.out.println("  expected: " + expected[i]);
            System.out.println("  got:      " + got);
            System.out.println("  MATCH: " + match);
            System.out.println();
        }
        System.out.println(allMatch ? ">>> 全部通过 <<<" : ">>> 有失败! <<<");
    }

    static String generate(String urlParams, String userAgent, int timer) {
        byte[] rc4Ua = rc4Encrypt(UA_KEY, userAgent.getBytes(LATIN1));
        String b64Ua = Base64.getEncoder().encodeToString(rc4Ua);
        int[] array1 = md5StrToArray(md5Hex(b64Ua));
        int[] array2 = md5StrToArray(md5Hex(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")));
        int[] urlParamsArray = md5Encrypt(urlParams);

        int ct = 536919696;
        int[] newArray = new int[]{
            64, 0, 1, 12,
            urlParamsArray[14], urlParamsArray[15],
            array2[14], array2[15],
            array1[14], array1[15],
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
        garbled[0] = 2;
        garbled[1] = (byte) 0xFF;
        System.arraycopy(rc4Result, 0, garbled, 2, rc4Result.length);
        String garbledStr = new String(garbled, LATIN1);

        StringBuilder sb = new StringBuilder();
        char[] chars = garbledStr.toCharArray();
        int ci = 0;
        while (ci + 2 < chars.length) {
            sb.append(calculation(chars[ci] & 255, chars[ci + 1] & 255, chars[ci + 2] & 255));
            ci += 3;
        }
        return sb.toString();
    }

    static int[] md5StrToArray(String s) {
        if (s.length() > 32) {
            int[] r = new int[s.length()];
            for (int i = 0; i < s.length(); i++) r[i] = s.charAt(i);
            return r;
        }
        int[] out = new int[s.length() / 2];
        int idx = 0, oi = 0;
        while (idx + 1 < s.length()) {
            int hi = HEX_TABLE[s.charAt(idx)];
            int lo = HEX_TABLE[s.charAt(idx + 1)];
            out[oi++] = ((hi << 4) | lo) & 0xFF;
            idx += 2;
        }
        return out;
    }

    static int[] md5Encrypt(String urlParams) {
        String m1 = md5Hex(urlParams);
        int[] arr = md5StrToArray(m1);
        String m2 = md5Hex(arr);
        return md5StrToArray(m2);
    }

    static String md5Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String md5Hex(int[] input) {
        byte[] bytes = new byte[input.length];
        for (int i = 0; i < input.length; i++) bytes[i] = (byte) input[i];
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] rc4Encrypt(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) % 256;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[data.length];
        int x = 0, yy = 0;
        for (int k = 0; k < data.length; k++) {
            x = (x + 1) % 256;
            yy = (yy + s[x]) % 256;
            int t = s[x]; s[x] = s[yy]; s[yy] = t;
            out[k] = (byte) ((data[k] & 0xFF) ^ s[(s[x] + s[yy]) % 256]);
        }
        return out;
    }

    static String calculation(int a1, int a2, int a3) {
        int x3 = ((a1 & 255) << 16) | ((a2 & 255) << 8) | (a3 & 255);
        return "" + CHARACTER.charAt((x3 & 16515072) >> 18)
                + CHARACTER.charAt((x3 & 258048) >> 12)
                + CHARACTER.charAt((x3 & 4032) >> 6)
                + CHARACTER.charAt(x3 & 63);
    }
}
