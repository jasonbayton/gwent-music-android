# Third-party notices

The application's MIT licence applies to project-authored code, not to third-party
fonts, artwork or dependencies. Font licence files are packaged in the APK under
`assets/licenses/` as well as retained in source.

## Open Sans

- Bundled file: `app/src/main/res/font/opensans.ttf`, version 3.003.
- Copyright 2020 The Open Sans Project Authors (https://github.com/googlefonts/opensans).
- Licence: [SIL Open Font License 1.1](app/src/main/assets/licenses/opensans-OFL.txt).
- [Upstream font and licence](https://github.com/google/fonts/tree/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/opensans).
- SHA-256: `36643644f318a812aab2d2ed3bb98f8cf0872527f835fe9398d95fe6b9adb878`.

## Raleway

- Bundled file: `app/src/main/res/font/raleway.ttf`, version 4.026.
- Copyright 2010 The Raleway Project Authors (impallari@gmail.com), with Reserved Font Name "Raleway".
- Licence: [SIL Open Font License 1.1](app/src/main/assets/licenses/raleway-OFL.txt).
- [Upstream font and licence](https://github.com/google/fonts/tree/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/raleway).
- SHA-256: `8bbcc3eb8275c388f4bcd998832f8a4b943eadbaf6a595205312774b5951aefb`.

Both bundled fonts exactly match the unmodified upstream variable-font bytes at
the pinned Google Fonts revision above, checked on 5 September 2026. Local file
renaming does not change font contents.

## Treble-clef artwork

`app/src/main/res/drawable/gmc_logo.xml` uses the path from
[GClef.svg on Wikimedia Commons](https://commons.wikimedia.org/wiki/File:GClef.svg),
attributed there to contributor **っ**, with the current file dated 2 October 2012.
Commons marks it public domain because it consists of common-property information
without original authorship. The path matches the upstream SVG; the Android vector
adds white fill, positioning and scaling. The launcher foreground references this
drawable. This is not an official Gwent Music logo or an endorsement.

## Dependencies

The [release dependency inventory and embedded notices](app/src/main/assets/licenses/dependency-notices.txt)
cover all resolved `releaseRuntimeClasspath` components, including transitive and
metadata-only modules. Full [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt)
terms and the embedded Jakarta Inject NOTICE are included. The inventory records
artefact checksums and preserves licence/NOTICE texts found inside AARs and nested
JARs, conservatively including lint artefacts before shrinking.

OkHttp also embeds the Public Suffix List dataset, which has separate
[MPL 2.0 terms](app/src/main/assets/licenses/MPL-2.0.txt) and a
[dataset notice](app/src/main/assets/licenses/public-suffix-NOTICE.txt).
Its corresponding source is available in the
[OkHttp 5.5.0 source tree](https://github.com/square/okhttp/tree/a94bdf152084d11acecd44dcd09ffef203f4f0aa/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix).
The generator for the compiled dataset is also available in that source tree.
These terms apply to the dataset, not to the application's independent MIT code.

All notice files under `app/src/main/assets/licenses/` are packaged with the APK.
To regenerate after dependency changes:

```sh
./gradlew -I scripts/dependency-inventory.init.gradle :app:publicationDependencyInventory
python3 scripts/generate-dependency-notices.py
python3 scripts/generate-dependency-notices.py --check
```

Review new licences and any embedded assets when dependencies change. CI checks
the committed inventory against resolved artefacts; unknown module licences fail
closed. Build tools and test-only dependencies are not part of this release-runtime
inventory and retain their own licences.
