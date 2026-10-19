# UIT Mobile

Native Android phone application scaffold using Kotlin and Jetpack Compose.
The initial project uses JDK 21, Android SDK 35 and the verified Gradle wrapper.
Set the SDK location in an untracked `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
sh scripts/install-git-hooks.sh
```

Modules will be introduced alongside their implementations. Follow the
[commit message contract](docs/COMMIT-CONTRACT.md) and keep local credentials,
signing material, caches and generated APKs out of Git. Source attribution is in
[NOTICE](NOTICE).
