import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** CVE-2026-91403 mitigation: https://ops.java/emudhra (2026-10-24). */
public final class TrustStorePolicy {
    private static final char[] PASSWORD = "changeit".toCharArray();
    // Match certificate identity, not vendor-specific aliases (including duplicates).
    private static final Set<String> DISTRUSTED = Set.of(
        "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b", // emSign ECC G3
        "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4", // AAA
        "40f6af0346a99aa1cd1d555a4e9cce62c7f9634603ee406615833dc8c8d00367", // emSign G1
        "1aa0c2709e831bd6e3b5129a00ba41f7eeef020872f1e6504bf0f6c3f24f3af3"  // emSign G2
    );

    static boolean distrusted(Certificate certificate) throws Exception {
        return DISTRUSTED.contains(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
    }

    static void sanitize(Path path) throws Exception {
        // Resolve OS-managed cacerts symlinks without replacing the link itself.
        path = path.toRealPath();
        KeyStore store = KeyStore.getInstance(path.toFile(), PASSWORD);
        int removed = 0;
        for (String alias : Collections.list(store.aliases())) {
            if (distrusted(store.getCertificate(alias))) {
                if (!store.isCertificateEntry(alias)) {
                    throw new IllegalStateException("Refusing to modify private-key entry: " + alias);
                }
                store.deleteEntry(alias);
                removed++;
            }
        }
        if (store.size() == 0) {
            throw new IllegalStateException("Refusing an empty trust store: " + path);
        }
        if (removed > 0) {
            var bytes = new ByteArrayOutputStream();
            store.store(bytes, PASSWORD);
            Files.write(path, bytes.toByteArray());
        }
        System.out.println("Trust store: removed " + removed + " entries from " + path);
    }

    static void checkDefaultTrust() throws Exception {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        int trusted = 0;
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (var certificate : x509.getAcceptedIssuers()) {
                    if (distrusted(certificate)) {
                        throw new IllegalStateException("Distrusted root remains: "
                            + certificate.getSubjectX500Principal());
                    }
                    trusted++;
                }
            }
        }
        if (trusted == 0) {
            throw new IllegalStateException("No trusted X.509 issuers available");
        }
        System.out.println("CVE-2026-91403 trust check passed; trusted issuers=" + trusted);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("sanitize")) {
            sanitize(Path.of(args[1]));
        } else if (args.length == 0) {
            checkDefaultTrust();
        } else {
            throw new IllegalArgumentException("Usage: TrustStorePolicy [sanitize CACERTS]");
        }
    }
}
