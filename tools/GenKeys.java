import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/**
 * One-time: creates the licence signing key pair.
 *   java tools/GenKeys.java <folder-to-save-private-key>
 * Prints the PUBLIC key (paste into app/src/main/java/com/safetap/sos/License.kt).
 * The PRIVATE key is saved as license_private.b64 in the folder. Keep it secret and backed up;
 * never commit it. Anyone with it can create activation codes.
 */
public class GenKeys {
    public static void main(String[] a) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        KeyPair kp = g.generateKeyPair();
        Path out = Paths.get(a[0]).resolve("license_private.b64");
        Files.writeString(out, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        System.out.println("Private key saved to: " + out);
        System.out.println("PUBLIC_KEY=" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
    }
}
