# Forum mobile reading preview

This preview improves the native reading flow and error reporting. Full Discourse
parity remains tracked in [the Forum acceptance matrix](research/UIT-FORUM-PARITY.md).
The owner's reported connection failure on the Itel S23 must still be checked on
that device; a successful host read is not proof of its cause.

## Install and find the reported topic

Use the [Forum preview prerelease](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/tag/v0.1.7-forum-preview.1).
The APK is named `UIT-Mobile-forum-preview.apk`, with launcher label **UIT Mobile
Preview** and application ID `vn.edu.uit.mobile.preview`. It installs separately
from the normal app. It uses the same preview package as the earlier course-search
preview; a compatible signed update preserves that preview installation's data.

[Download the APK](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/download/v0.1.7-forum-preview.1/UIT-Mobile-forum-preview.apk).
Version: `0.1.7-forum-preview.1`, version code 7, size 25,924,109 bytes.
SHA-256: `f855896d43ae4502539550cbd906f750ec35252ee5127f81394fdb8c9c0ac5b5`.

The APK contains no university account, API key, private source capture or saved
Forum session. Forum reading does not require an OpenAI key.

1. Open **Inbox → UIT Forum**.
2. Search for `ICPC 2026`, then open the announcement matching
   [topic 163707](https://forum.uit.edu.vn/t/163707).
3. Confirm that the title and body are readable. The observed stream has posts
   numbered **1, 16, 17, 19 and 20**; these are source numbers, not a sequence the
   app should renumber. Counts may change on the live server.
4. Use the compact **Post #…** control to jump to a source post number. The number
   field appears on demand rather than permanently occupying the reading area.
5. Scroll to a post footer. Like, copy-link, overflow and permitted Reply controls
   are grouped there. Secondary topic actions, including AI, are in the topic
   overflow menu.

## Inline images and viewing

Safe same-origin `/uploads/` previews load when they enter the reading viewport.
There is no normal Load image step for those previews. Placeholder geometry uses
reported dimensions with bounded height; decoded bytes/pixels remain bounded.
Tap the image itself to open the original-image viewer with fit/zoom/pan/close.

Failures show an explicit Retry action. Concealed spoilers/details do not fetch
nested images until expanded. External/unsupported image delivery still has an
explicit browser handoff; CDN/secure-upload and multi-image gallery coverage remain
partial rather than claimed as full parity.

A byte cache is memory-only, limited to 8 MiB and 12 entries. Reads are serialized,
URLs are reused within the active scope, and account/public-source transitions or
closing Forum invalidate the scope. Late results cannot repopulate an invalidated
scope. Original/preview image URLs remain subject to the existing origin policy;
credentials are not forwarded to arbitrary image hosts.

## Guest, sign-in and account actions

Forum has its own session. Being signed in to Moodle/Portal does not automatically
provide a Forum cookie. The university credentials can match while the sessions
remain separate.

Guest controls remain visible. An explicit interaction can open Forum sign-in;
after successful sign-in the app returns to that post. It does not replay a like,
bookmark or submission automatically. Member actions still use the actual topic
and post permissions returned by the server. Copying a source link is available
without signing in.

For read-only acceptance, open menus/forms, inspect permissions and copy links.
Once signed in, reacting, saving a Forum bookmark or sending a reply is a real
server operation. Research tests on the owner's account stop before those actions;
mutation callbacks/contracts are tested with isolated fixtures and the existing
Discourse lab coverage.

## If the ICPC request still fails on the phone

A failed new topic now retains its known title and clears the previous topic's
window and retrieval timestamp. An empty topic does not advertise AI or phantom
loaded counts. Retry and Open source remain available.

Open **Error details → Copy diagnostic details**. The clipboard text includes:

- Topic ID and the current anchor post number.
- Failure kind and a typed connection category, when one was observed.
- Guest/member mode, without the account identity.
- The time the diagnostic text was copied.

It excludes credentials, cookies, raw exception messages, response bodies, source
excerpts and search query text. It is copied locally only after the user requests
it; there is no automatic diagnostic upload.

| Category | What the app observed |
| --- | --- |
| `DNS` | Hostname resolution failed |
| `TLS` | Secure handshake failed; certificate checks remain enabled |
| `TIMEOUT` | Connection/request transfer exceeded a timeout |
| `CONNECT` | A connection could not be established |
| `IO` | Another connection/transfer interruption |
| `SERVER` | The Forum returned an HTTP server-error status |
| `UNKNOWN` / `UNRECORDED` | The request did not provide a more specific recorded category |

Chrome opening the topic establishes that the browser can reach it. It does not
identify which of these categories affected the app's earlier request. Likewise,
the valid gaps in post numbers are not evidence of a transport failure.

## Verification and reproduction

See [the research and actual-source evidence](research/UIT-FORUM-MOBILE-UX-2026-11-05.md)
and [the phone gallery](design/FORUM-READING-UX.md). Real-source captures use live UIT
responses, not authored posts. Authenticated account captures stay private.

Build the side-by-side APK:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew :app:assembleDebug \
  -PpreviewInstall=true -PpreviewVersion=0.1.7-forum-preview.1
```

The manual public transport probe uses no credentials and performs three bounded
GETs through the production client/parser:

```sh
./gradlew :core:network:forum:researchForumReadingUx
```

Its aggregate output is under the module's ignored `build/outputs/forum-reading-ux/`
directory. The installed Android test is separately opt-in:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -PpreviewInstall=true -PpreviewVersion=0.1.7-forum-preview.1 \
  -Pandroid.testInstrumentationRunnerArguments.class=vn.edu.uit.mobile.ForumReadingLiveNativeTest \
  -Pandroid.testInstrumentationRunnerArguments.forumPublicLive=true
```

That test accepts only public GETs to the Forum, with no cookies or credentials.
It hosts the real Forum components in a native test Activity with in-memory
repository storage. It does not certify MainActivity navigation, production
Keystore persistence, every OEM's TLS stack or the physical Itel S23.

For this preview, the installed test compiled but was not executed. A blank,
isolated API 30 software emulator had an unstable package service and could not
install the APK. The complete repository checks, five live public host render
tests and the guarded actual-account Kotlin host read passed. No physical-phone
acceptance is claimed. The earlier preview and this APK use the same debug signing
certificate; the preview is a development build, not a signed production release.
