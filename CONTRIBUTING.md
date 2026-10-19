# Contributing to UIT Mobile

UIT Mobile targets Android phones. Keep the single-column phone flows, native
bottom navigation, transparent UIT identity and asymmetric cut-corner frames.
Repository content and commit messages are English. Read [AGENTS.md](AGENTS.md)
and the [current phone design rules](.interface-design/system.md).

Set up JDK 21 and Android SDK 35 as described in [README.md](README.md). Then enable
the repository hooks:

```sh
sh scripts/install-git-hooks.sh
```

Follow the [commit message contract](docs/COMMIT-CONTRACT.md). Commit coherent
changes with accurate validation notes. Keep local SDK paths, signing material,
cookies, credentials, APKs, Gradle caches and build directories out of Git.
The Gradle wrapper JAR, Room schema, fonts, asset licenses and curated review
screenshots are intentionally versioned.

Before submitting a change, run the checks appropriate to its scope:

```sh
./gradlew :app:assembleDebug check
python3 scripts/check-repo-hygiene.py
```

For UI changes, inspect real phone renders at 360dp, 390dp and 412dp. See the
[phone gallery](docs/design/README.md) and [SSH debugging guide](docs/DEBUGGING.md).
Passing tests establish tested behavior, not design approval.
