# Signing

Release APKs are signed with a 4096-bit RSA key (`CN=KScanner`, valid until 2056).
Certificate SHA-256 fingerprint:

```
B9:89:24:A5:BA:36:4B:67:AC:61:83:75:50:E3:D2:84:5E:3B:1C:05:AC:95:A4:2B:DE:B9:52:30:63:80:66:FD
```

Users can confirm a download came from this project by comparing that digest against
`apksigner verify --print-certs app-release.apk`.

## How the build finds the key

`app/build.gradle.kts` reads four values, first from `keystore.properties` at the repository
root and then from the environment:

| Property        | Environment variable      |
| --------------- | ------------------------- |
| `storeFile`     | `KSCANNER_KEYSTORE_FILE`  |
| `storePassword` | `KSCANNER_STORE_PASSWORD` |
| `keyAlias`      | `KSCANNER_KEY_ALIAS`      |
| `keyPassword`   | `KSCANNER_KEY_PASSWORD`   |

If all four are present and the keystore file exists, the `release` build type is signed.
Otherwise `assembleRelease` still succeeds and produces an unsigned APK, so a fresh clone can
build and run the tests without anyone's private key.

`keystore.properties`, `*.jks` and `*.keystore` are git-ignored. Do not commit them.

APK Signature Scheme **v2 and v3** are used. v1 (JAR signing) is off: `minSdk` is 26 and v2
ships from API 24, so no supported device needs it, and leaving it on would add an unused
META-INF signature and the v1-only downgrade class with it.

## Local setup

```bash
cat > keystore.properties <<'EOF'
storeFile=/absolute/path/to/kscanner-release.jks
storePassword=...
keyAlias=kscanner
keyPassword=...
EOF
chmod 600 keystore.properties

./gradlew :app:assembleRelease
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Note that PKCS12 keystores use the store password for the key as well, so `keyPassword` is
normally identical to `storePassword`.

## CI

The `Release` workflow (`.github/workflows/release.yml`) reads the key from four repository
secrets:

| Secret                      | Contents                                  |
| --------------------------- | ----------------------------------------- |
| `RELEASE_KEYSTORE_BASE64`   | the `.jks`, base64-encoded on one line    |
| `RELEASE_STORE_PASSWORD`    | the store password                        |
| `RELEASE_KEY_ALIAS`         | `kscanner`                                |
| `RELEASE_KEY_PASSWORD`      | the key password                          |

They are set from files rather than an argument list so the values never appear in shell
history or a process listing:

```bash
base64 -w0 kscanner-release.jks > ks.b64
gh secret set RELEASE_KEYSTORE_BASE64 < ks.b64
gh secret set RELEASE_STORE_PASSWORD  < pw.txt
gh secret set RELEASE_KEY_PASSWORD    < pw.txt
gh secret set RELEASE_KEY_ALIAS       < alias.txt
rm ks.b64 pw.txt alias.txt
```

The workflow verifies the decoded keystore with `keytool -list` before building, fails if any
secret is missing, and runs `apksigner verify` on the finished APK so an unsigned or
broken-signature build can never be published.

## Cutting a release

```bash
git tag v1.2.0
git push origin v1.2.0
```

Tags must be `vMAJOR.MINOR.PATCH`. Both version fields come from the tag, so rebuilding a
tag reproduces the same APK version:

| Tag       | `versionName` | `versionCode` |
| --------- | ------------- | ------------- |
| `v1.0.0`  | `1.0.0`       | `10000`       |
| `v1.2.3`  | `1.2.3`       | `10203`       |
| `v2.0.1`  | `2.0.1`       | `20001`       |

Minor and patch must stay below 100 for the encoding to be unambiguous. Never reuse or move a
published tag with a different number: Android blocks installing an APK whose `versionCode` is
lower than the installed one, so number releases upward.

The workflow also accepts a manual `workflow_dispatch` with a tag input, creating the tag if
needed, and verifies afterwards that the built APK really reports the expected version.

## Backing up

The keystore is the app's identity. Losing it means future releases cannot be installed as an
update over existing installs — users would have to uninstall first. Keep an offline copy of
both the `.jks` and its password. If it is ever compromised, the only remedy is a new key and
a version-number reset, so treat it as a secret.
