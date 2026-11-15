# UMA native phone review

Actual UMA scholarship replies are replayed through the production JSON/Markdown
adapter and native Compose chat. The owner authorized authentication and ordinary
read-only questions. Reply content is not authored fixture text. Owner identity
alone is replaced with an explicit hidden-account label in the public renders;
no GPA, transcript, course enrollment or session credentials are published here.

The network capture occurred at 2026-11-11 21:06 UTC (2026-11-12 in Japan/Vietnam).
The [aggregate network record](../research/evidence/uma-native-live.json) and
[actual public-source reply](../research/evidence/uma-public-reply.json) distinguish
production networking from host rendering. The private GPA response and its render
were inspected separately and remain outside the public repository.

These images use production Compose under Robolectric, SDK 35, native graphics,
UIT dark palette with dynamic colors disabled. They are not physical-phone captures.
The surrounding shell has no imported Portal snapshot and therefore retains its
Connect your account label; the UMA session/identity is independent. Device/OEM
WebView and keyboard behavior still need phone acceptance.

| View | Evidence |
| --- | --- |
| 360dp, Vietnamese | [Chat](previews/uma/uma-chat-360.png) |
| 390dp, English controls and original Vietnamese reply | [Chat](previews/uma/uma-chat-390.png) |
| 412dp, Vietnamese, 1.3× font scale | [Chat](previews/uma/uma-chat-412-large-text.png) |
| Expanded citation, original document and scan/age warnings | [Sources](previews/uma/uma-sources-390.png) |

The composer, five-tab shell, 88% user frame, reading-canvas reply and asymmetric
cuts follow the existing design system. The upper source control exposes the
verified UMA identity, account identifier and selected scope. It is independent
of OpenAI connection settings. Sources remain collapsed until requested. Question
suggestions and week navigation fill the composer without silently sending.

The actual response exposed an empty-table placeholder beside populated GPA rows;
the native adapter now shows that placeholder only for an empty table. Regression
tests retain the case. The Latest message control targets a final list sentinel,
so a long single reply can scroll to its end. Citation cards retain explicit
browser actions; the app does not forward UMA credentials to those destinations.

Reproduce public rendering without university credentials:

```sh
mkdir -p /tmp/uit-uma-public-preview
cp docs/research/evidence/uma-public-reply.json \
  /tmp/uit-uma-public-preview/regulation-response-private.json
./gradlew :app:testDebugUnitTest \
  --tests '*UmaDesignTest.actualUmaReply*' \
  -PumaCapture=/tmp/uit-uma-public-preview -PrecordUi=true
```

The separate personal-render method requires an owner-authorized private capture;
it is skipped during ordinary tests. Never substitute synthetic personal content
and label it as actual UMA evidence. The public replay uses only the public-source
reply and a visibly hidden owner label, with no sign-in or question request.
