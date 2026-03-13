import java.util.Base64;

public class FindBase64Hex {
    public static void main(String[] args) {
        String chars = "0123456789abcdef";
        int maxLen = 6;
        for (int len = 1; len <= maxLen; len++) {
            if (search(len, chars)) return;
        }
        System.out.println("No match found");
    }

    private static boolean search(int len, String chars) {
        int total = (int) Math.pow(chars.length(), len);
        for (int i = 0; i < total; i++) {
            StringBuilder sb = new StringBuilder(len);
            int v = i;
            for (int j = 0; j < len; j++) {
                sb.append(chars.charAt(v % chars.length()));
                v /= chars.length();
            }
            String s = sb.toString();
            String base64 = Base64.getEncoder().encodeToString(s.getBytes());
            if (base64.matches("[0-9a-f]+") && base64.length() % 8 == 0) {
                System.out.println("found s=" + s + " base64=" + base64 + " len=" + base64.length());
                return true;
            }
        }
        return false;
    }
}
