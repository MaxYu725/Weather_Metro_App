# Weather Metro 1.2.0 — App slimming and signed release contract

## Scope

This release cycle starts from `main@eb212865f7dc0bb7c86574fb9eebc88ae7a72792` after Notification 2D2 was completed and real-device verified.

The objective is to reduce Android release delivery size without removing Rain, Storm, MapLibre, notification reliability, supported CPU architectures, or any production data source.

## Baseline

The existing release build already enabled R8 minification and Android resource shrinking.

Measured production-style unsigned artifacts before slimming:

| Artifact | Bytes | MiB |
| --- | ---: | ---: |
| universal APK | 61,042,939 | 58.22 |
| full-ABI AAB | 26,039,404 | 24.83 |

APK inspection showed that the dominant payload was MapLibre native code. Four uncompressed `libmaplibre.so` files accounted for approximately 58.6 MB of raw APK contents:

- `armeabi-v7a`: 11,253,064 bytes
- `arm64-v8a`: 15,429,024 bytes
- `x86`: 15,989,700 bytes
- `x86_64`: 15,923,656 bytes

Application-owned images/resources and the optimized DEX were comparatively small, so deleting UI assets or product functionality would not produce a meaningful reduction.

## Dependency / R8 cleanup

The unused Fragment Kotlin extension artifact was replaced by the smaller base `androidx.fragment:fragment` runtime. The base Fragment dependency is retained because AndroidX ActivityResult release lint requires Fragment 1.3+ on the runtime classpath even though Weather Metro does not directly use Fragment APIs.

The app-level blanket rule:

```text
-keep class com.google.firebase.** { *; }
```

was removed. Firebase Messaging remains protected by its SDK-provided consumer rules instead of disabling R8 optimisation for the entire Firebase namespace.

Measured after this cleanup:

| Artifact | Bytes | Change |
| --- | ---: | ---: |
| universal APK | 61,042,762 | -177 bytes |
| full-ABI AAB | 26,038,850 | -554 bytes |

This confirms that ordinary Java/Kotlin dependency trimming is not the material size lever for the current app.

## Release packaging

Weather Metro now has two intentional release delivery forms.

### Full-ABI AAB

The app bundle retains all four supported ABIs:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

The AAB remains suitable for an app store to generate device-specific delivery splits. Architectures are not removed merely to reduce the upload artifact size.

### Slim per-ABI APKs

When Gradle property `WEATHER_SLIM_RELEASE_APKS=true` is supplied, APK output is split by ABI, no universal APK is produced, and native libraries use compressed legacy APK packaging for smaller sideload downloads.

CI verifies that every APK contains exactly one `libmaplibre.so`, that the native payload is compressed, and that the AAB still contains all four ABIs.

Measured unsigned APK sizes:

| ABI | Bytes | MiB | Reduction vs 58.22 MiB universal APK |
| --- | ---: | ---: | ---: |
| arm64-v8a | 7,492,267 | 7.15 | 87.73% |
| armeabi-v7a | 6,956,629 | 6.63 | 88.60% |
| x86 | 7,788,153 | 7.43 | 87.24% |
| x86_64 | 7,741,818 | 7.38 | 87.32% |
| full-ABI AAB | 26,038,850 | 24.83 | upload/store artifact |

The compressed per-ABI APK strategy optimises sideload/download size. The full AAB remains the preferred store-distribution artifact.

## Signed release pipeline

`.github/workflows/android-release.yml` now:

1. derives `versionName` and `versionCode` from the app build configuration;
2. validates the four signing secrets without printing them;
3. decodes the keystore only into runner temporary storage;
4. builds/tests/lints the signed full-ABI AAB;
5. builds four signed compressed per-ABI APKs;
6. verifies the APK ABI/compression contract and full AAB ABI coverage;
7. verifies every APK with Android `apksigner`;
8. verifies the AAB JAR signature and reads its signing certificate;
9. creates `SHA256SUMS.txt`, `SIGNATURES.txt`, and `release-manifest.json`;
10. uploads one signed release artifact set.

The decoded keystore is deleted in an `always()` cleanup step and is never committed or uploaded.

A main-branch change to `app/build.gradle.kts` triggers this signed release workflow. Tag-based and manual release triggers are retained.

## Version

Weather Metro release version for this cycle:

- `versionName = 1.2.0`
- `versionCode = 4`

## Non-goals / preserved behaviour

This slimming cycle does not:

- remove MapLibre, Rain, or Storm functionality;
- remove supported ABIs from the app bundle;
- weaken R8/resource shrinking safety gates;
- alter Weather Metro location ownership;
- alter notification event semantics or durable state;
- alter the territory-wide one-minute HKO warning monitor;
- expose signing credentials;
- change data-source contracts.
