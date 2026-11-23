# Offline license documents

`generateAboutAssets` copies the root `LICENSE` plus the `.txt`
files here into `assets/licenses`. `AboutLicenseCatalog.kt` pairs the sources
and attributions with complete texts; Apache-2.0 is stored once and reused.
The app does not fetch license texts at runtime.
The root `NOTICE` remains in the repository; the standalone Project attribution
entry and the introductory text were removed from the app in ADR-072.

Sources verified on 2026-11-23:

- `APACHE-2.0.txt`: https://www.apache.org/licenses/LICENSE-2.0.txt
- `HEARTLEO-MIT.txt`: https://raw.githubusercontent.com/heartleo/zlib/main/LICENSE
- `MATERIALKOLOR-MIT.txt`: https://raw.githubusercontent.com/jordond/materialkolor/main/LICENSE
  (the resolved `material-color-utilities:6.0.0` POM declares MIT for the port
  and Apache-2.0 for upstream Material Color Utilities).
- `JAKARTA-NOTICE.txt`: `META-INF/NOTICE.md` from the resolved
  `jakarta.inject:jakarta.inject-api:2.0.1` JAR.
- `SHAREDVIEW-NOTICE.txt`: provenance and license boundary from root `NOTICE`
  and `app/libs/README.md`; the example's MIT license does not cover the SDK.

The family list covers the resolved Android runtime libraries (including
Miuix transitive dependencies), not Gradle plugins or test-only tools.
Review the offline catalog when runtime dependencies change. Published POM
license declarations and available embedded notices were used to identify
the license of each family, including the published FindBugs JSR-305 3.0.2
artifact's Apache-2.0 declaration.
