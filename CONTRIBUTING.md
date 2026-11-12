# Contributing

Thanks for helping! Bug reports, extension compatibility reports and pull requests are welcome.

## Build

Requirements: JDK 21.

```bash
./gradlew :app:run              # run from source
./gradlew :app:test             # tests
packaging/build-appimage.sh     # dist/Inkoma-<version>-x86_64.AppImage
```

## Guidelines

- Keep the look and behaviour consistent with Mihon, which Inkoma is based on (Material 3, same spacing and wording).
- Every change should keep `./gradlew :app:test` green. Add a test for new logic.
- Extension-related changes: run the converter on real extension APKs and make sure the classes still pass
  JVM verification (see `ExtensionPipelineTest` and `ConstructorFixTest`).
- Don't add features that require a browser: the app is meant to be fully self-contained.

## Reporting extension problems

Please include the extension name and version and the exact error text from Browse › Extensions.
