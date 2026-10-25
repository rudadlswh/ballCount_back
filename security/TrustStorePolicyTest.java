import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;

/** Standalone regression checks; fixtures contain public CA certificates only. */
public class TrustStorePolicyTest {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        ArrayList<Certificate> roots;
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            roots = new ArrayList<>(CertificateFactory.getInstance("X.509").generateCertificates(input));
        }
        if (roots.size() != 5) throw new AssertionError("Expected four distrusted roots and one control");
        for (String type : new String[] {"JKS", "PKCS12"}) {
            Path storePath = directory.resolve(type + ".cacerts");
            var store = KeyStore.getInstance(type);
            store.load(null, "changeit".toCharArray());
            for (int i = 0; i < 4; i++) {
                store.setCertificateEntry("vendor-alias-" + i, roots.get(i));
                store.setCertificateEntry("duplicate-" + i, roots.get(i));
            }
            store.setCertificateEntry("legitimate-control", roots.get(4));
            try (var output = Files.newOutputStream(storePath)) {
                store.store(output, "changeit".toCharArray());
            }
            System.setProperty("javax.net.ssl.trustStore", storePath.toString());
            System.setProperty("javax.net.ssl.trustStoreType", type);
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
            expectRejected();
            Path link = directory.resolve(type + ".link");
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, storePath.toAbsolutePath());
            TrustStorePolicy.sanitize(link);
            if (!Files.isSymbolicLink(link)) throw new AssertionError("Symlink replaced");
            var clean = KeyStore.getInstance(storePath.toFile(), "changeit".toCharArray());
            if (clean.size() != 1 || !Arrays.equals(roots.get(4).getEncoded(),
                    clean.getCertificate("legitimate-control").getEncoded())) {
                throw new AssertionError("Distrusted aliases remain or legitimate certificate changed");
            }
            TrustStorePolicy.checkDefaultTrust();
            byte[] first = Files.readAllBytes(storePath);
            TrustStorePolicy.sanitize(link);
            if (!Arrays.equals(first, Files.readAllBytes(storePath))) {
                throw new AssertionError("Sanitation is not idempotent");
            }
            // An override pointing at an empty trust store must fail closed at startup.
            clean.deleteEntry("legitimate-control");
            try (var output = Files.newOutputStream(storePath)) {
                clean.store(output, "changeit".toCharArray());
            }
            expectRejected();
        }
        System.out.println("PASS: duplicate aliases, JKS/PKCS12, symlinks, control preservation, idempotence, empty stores");
    }

    private static void expectRejected() throws Exception {
        try {
            TrustStorePolicy.checkDefaultTrust();
            throw new AssertionError("Unsafe default trust was accepted");
        } catch (IllegalStateException expected) {
            // Expected policy rejection; parsing/loading errors must still fail the test.
        }
    }
}
