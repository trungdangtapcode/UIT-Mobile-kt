# Test the course-search update preview

## Install without replacing your current app

The preview build uses package `vn.edu.uit.mobile.preview`, with launcher label
**UIT Mobile Preview**. It installs alongside `vn.edu.uit.mobile` and has separate
Android app storage, accounts and AI configuration. Do not uninstall your current
app to test this build. A debug APK is a test artifact, not a production release.

The APK contains no university account, API key, private notebook or saved index.
Sign in to Moodle using the normal app flow, then download the course materials you
want to search. Only configure a personal Direct OpenAI key if you choose semantic
preparation; keyword preparation and saved-file checks need no AI connection.
The embedding model defaults to `text-embedding-3-large` with 512 dimensions;
your chat model can stay `gpt-6-luna`. Model availability depends on your API project.

Download the [preview APK](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/download/v0.1.7-search-preview.1/UIT-Mobile-search-preview.apk) and `SHA256SUMS` from the [prerelease](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/tag/v0.1.7-search-preview.1). The APK SHA-256 is:

```text
095af05e831831134fda0d8d51eaa6102ab7da1ef4a8efbd701c13f683553158
```

## Try the main workflow

1. Open the toolbar Search action, then **Search inside downloaded materials**.
2. Choose your course and open **Files & updates**.
3. Select a downloaded notebook (`.ipynb`), Markdown/text/code file, or supported
   text PDF. Undownloaded files explain how to get a saved copy first.
4. Leave **Search by meaning** off and tap **Check saved files**. This must work
   without an AI key and must not submit a provider request.
5. Open **Review search update**. It should explicitly say no AI request is needed.
   Confirm **Update on this phone**. Return to Search and try a keyword/code identifier.
6. To try semantic search, configure your own Direct connection, enable **Search
   by meaning**, and check again. The review must disclose new unique text passages,
   character count, model/dimensions, provider transfer and API usage before sending.
7. Confirm only if you want to send that selected course text to OpenAI. After the
   update, open **Search details** from the overflow menu to inspect the receipt.
8. Check and update the same unchanged file again. The plan should show all saved
   passage vectors reused, zero new texts and no AI request needed. The successful
   receipt should show zero embedding requests for this second update.

The reused count is passage rows; new transfers count unique text. They can differ
when the material contains repeated identical text. Search queries by meaning
still make their own explicit embedding request when you press Search.

## Check freshness and recovery

- The check timestamp is **Saved files checked**. It is not a Moodle server check.
  To test a genuine university source update, download the current file again from
  the course using normal app controls, then check its saved bytes here.
- Turning off meaning search and confirming replaces saved AI search with keyword-only search. The review warns that enabling it again needs new embeddings.
- Add/remove a selected file and verify the prior review disappears. Excluding a
  file changes the search scope and retains the original download.
- Stop an update and verify that no partial semantic index becomes active. Sent
  provider requests may still be billed. Retry starts from committed reusable
  vectors; interrupted new batches are not durably checkpointed.
- An API failure keeps an existing smart-search index. If there was no previous
  smart-search index, keyword coverage may be saved with an explicit AI failure.
- Missing/unsupported files block the update; deselect or redownload them.
- Review with larger Android font sizes and in light/dark appearance. Actions must
  remain reachable as the list/dialog body scrolls.

Do not edit university content to create a test change. The account verification
policy remains read-only. Mutation, permission and file-change race cases are tested
with isolated automated fixtures, not by modifying the owner's university account.

## Evidence and limitations

The local verification restored a real authorized **NT409.R11** notebook capture
and its actual OpenAI vectors, migrated codec v1 to v2, then reused **103/103**
passage vectors with **zero provider calls and zero university requests**. This
proves reuse for that captured source, not freshness against Moodle today.

Native Compose phone renders cover 360dp, 390dp, 412dp with larger text, and light
appearance. They use the captured notebook and production check/update path.
They are host renders, not screenshots from a physical phone. No Android device is
attached to the SSH server; phone installation and interaction remain your acceptance
step. PDF page-aware retrieval, OCR and cross-device/background synchronization are
outside this preview.

## Reproduce the preview build

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew :app:assembleDebug -PpreviewInstall=true
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
Without `-PpreviewInstall=true`, the normal application package and label remain.
Never distribute an APK containing local credentials or copied private captures.
