# Java trust-store policy

CVE-2026-91403 is mitigated by distrusting emSign ECC G3 and AAA Certificate
Services. emSign G1/G2 are also removed following the 2026-10-24
[Oracle guidance](https://ops.java/emudhra). `TrustStorePolicy` matches SHA-256
root certificate identities across all aliases, including vendor duplicates.
This is root removal, not an intermediate-certificate fingerprint blocklist.

The Docker build sanitizes the build JDK before Gradle downloads and the Alpine
JRE after APK updates. It also requires `ca-certificates >= 20261020-r0`.
The entrypoint verifies the effective default trust manager before starting
Spring; an unsafe override or empty store prevents startup. Normal hostname
and certificate-chain validation remain enabled.

Run the focused checks with JDK 17 or later, from the repository root:

```sh
check_dir=$(mktemp -d)
javac --release 17 -d "$check_dir" security/TrustStorePolicy*.java
java -cp "$check_dir" TrustStorePolicyTest security/test-roots.pem "$check_dir/stores"
sh security/test-entrypoint.sh
```

`test-roots.pem` contains only public CA certificates (the four removed roots
and ISRG Root X1 as the unaffected control).

For deployment, build with `docker build --pull --no-cache -t kbo-back .` and
verify startup logs contain `CVE-2026-91403 trust check passed`. Rebuilds must
keep the runtime sanitation step after any package/trust-store updates.
Changing the repository alone does not update an already running container.

For a standalone JDK, back up its `cacerts` before running
`TrustStorePolicy sanitize PATH_TO_CACERTS`, then check that JDK's effective
trust store and restart its JVMs. Preserve OS-managed symlinks. Recheck after
JDK upgrades or reinstalls, which can replace the store.
