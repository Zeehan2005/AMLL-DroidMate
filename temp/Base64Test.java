import java.util.Base64;
public class Base64Test {
  public static void main(String[] args) {
    String bogusBase64 = "48656c6c6f576f726c64";
    String base64 = Base64.getEncoder().encodeToString(bogusBase64.getBytes());
    System.out.println("base64=" + base64);
    System.out.println("looksLikeHex=" + base64.toLowerCase().matches("[0-9a-f]+") + " length=" + base64.length());
    try {
      String decoded = new String(Base64.getDecoder().decode(base64));
      System.out.println("decoded=" + decoded);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
