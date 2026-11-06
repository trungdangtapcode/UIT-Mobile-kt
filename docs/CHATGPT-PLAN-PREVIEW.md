# ChatGPT account preview: owner acceptance

[Download the preview APK](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/download/v0.1.7-chatgpt-preview.3/UIT-Mobile-chatgpt-preview.apk)
from the [prerelease](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/tag/v0.1.7-chatgpt-preview.3).
The release includes `SHA256SUMS`. Launcher: **UIT Mobile Preview**; application ID:
`vn.edu.uit.mobile.preview`; version: `0.1.7-chatgpt-preview.3`.

Preview 3 preserves the catalog-loading indicator across credential refresh and
includes a native recovery regression for large catalogs after app recreation.
See the [latest verification evidence](research/evidence/chatgpt-native-acceptance.json)
for the live browser observation, controlled native checks and remaining device
acceptance.

This is a debug preview installed separately from the normal app. It updates the
earlier Forum/search preview using the same package and signing certificate;
updating preserves that preview's local data. Uninstalling deletes its local data.
The APK contains no account, OAuth session, API key or private source capture.

## Test the actual browser flow

This acceptance requires the owner's own eligible ChatGPT account. Complete login
on OpenAI's page in the phone's browser. Do not send passwords, OAuth codes or
tokens to another person, chat, CI or a shell session.

1. Open UIT Assistant and its AI connection settings. Course AI settings expose
   the same connection. Existing API/gateway configurations stay selected; choose
   **ChatGPT account** explicitly if one is already configured.
2. Tap **Continue with ChatGPT**. Confirm that the system browser opens the
   official OpenAI authorization page. Review the selected account/workspace and
   permission to use its ChatGPT plan. The app never asks for the password.
3. Complete authorization. The browser returns to a temporary `127.0.0.1` callback
   on the same phone. Tap Return to UIT Mobile on that page, or use Android Back.
4. Dismiss the first-use explanation. Confirm the account label and available
   model list, then tap **Use this ChatGPT account**. Do not treat signing in alone
   as activation or proof that inference is available.
5. In an existing workspace, select a public announcement or an explicit public
   Forum window. Ask one short source-backed question. Check the Using ChatGPT
   plan label, streaming preview and final citations. The request consumes the
   selected subscriber's available plan/app usage; this is not unlimited free AI.
6. Open a final citation and compare its retained quote with the selected source.
   A response rejected by the existing validator must stay an error, not become
   a saved successful answer.
7. Open Manage ChatGPT usage and confirm it opens ChatGPT settings. No remaining
   balance or reset time is invented in the app.

Do not test university posting, reactions, submissions, registration or payments.
The AI tool loop has no university write operation. Selected private material, if
used later, leaves the phone only after an explicit selection and Send action.

## Recovery and lifecycle checks

- If the account is already listed but no model picker is shown, update this
  preview in place and tap Refresh available models. Keep the saved registration;
  signing out is unnecessary for a catalog-size or response-format failure.
  Choose a model and Use this ChatGPT account only after the list loads. The Send
  control stays disabled until an AI connection is explicitly activated.
- If refresh still fails, use Copy connection diagnostics near the account row.
  Share that text when reporting the problem. It contains the operation, HTTP
  status (`0` means no HTTP status was captured), safe error code, request ID, response-shape
  category and, when available, the number of bytes read. It contains no account
  identity, browser URL, OAuth token, exception text or selected source material.
- Cancel browser consent, close the settings form, or exceed the three-minute
  timeout. The previous connection should remain usable and no AI call should
  have been sent. An unfinished attempt needs a fresh sign-in after process death
  or connection-screen recreation.
- Decline plan permission while allowing identity. The app can retain the sign-in
  but must not offer activation until plan permission and a model are available.
- Switch registrations and verify the account-specific model list and explicit
  activation. Signing in to another account must not replace an active connection
  before verification and selection.
- Sign out in the ChatGPT panel. Local tokens are cleared; reusable registration
  metadata remains. The app attempts remote renewable-session revocation. If it
  cannot confirm that operation, it retains an explicit warning and a management
  link. The registered app entry in ChatGPT may remain after session revocation.
- A quota/permission/service failure must not retry paid inference automatically
  or switch to another connection. Model availability comes from the service.

Local BM25 search works without this connection. ChatGPT authorization is not an
embeddings API credential; semantic embedding generation still uses Direct API.

## Evidence boundaries

The [architecture and official references](CHATGPT-PLAN.md) describe the protocol.
The [phone gallery](design/CHATGPT-SIGN-IN.md) contains empty forms on the host,
not connected-subscriber screenshots. Controlled tests use signed fixture tokens,
an actual localhost callback socket, mocked official endpoints and bounded SSE.
The public discovery endpoint was read without credentials to check metadata.
Those results do not establish subscriber eligibility or physical-phone success.

Build locally without GitHub Actions:

```sh
./gradlew :app:assembleDebug \
  -PpreviewInstall=true -PpreviewVersion=0.1.7-chatgpt-preview.3
```
