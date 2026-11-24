import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * After you confirm a customer's payment, make their activation code:
 *   java tools/LicenseGen.java <path-to-license_private.b64> <DEVICE-CODE>
 * DEVICE-CODE is the 8-character code (like A1B2-C3D4) shown on the app's payment screen.
 * Send the printed code back to the customer. It only works on that one device.
 */
public class LicenseGen {
    public static void main(String[] a) throws Exception {
        if (a.length != 2) {
            System.err.println("Usage: java tools/LicenseGen.java <license_private.b64> <DEVICE-CODE>");
            System.exit(1);
        }
        String device = a[1].trim().toUpperCase().replace("-", "").replace(" ", "");
        if (!device.matches("[0-9A-F]{8}")) {
            System.err.println("Device code must be 8 hex characters, e.g. A1B2-C3D4");
            System.exit(1);
        }
        byte[] pk = Base64.getDecoder().decode(Files.readString(Paths.get(a[0])).trim());
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pk));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(("PG1|" + device).getBytes("UTF-8"));
        System.out.println(Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign()));
    }
}
