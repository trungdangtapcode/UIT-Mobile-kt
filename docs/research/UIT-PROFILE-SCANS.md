# UIT profile scans

Investigation date: October 28, 2026. The account holder authorized read-only
inspection of their profile and implementation of in-app scan viewing. No profile
updates, uploads, academic submissions or administrative actions were performed.

## Observed representation

`GET https://portal.uit.edu.vn/sinh-vien/ho-so` embeds its profile in React Flight
records. Some profile fields, including `personal`, `family`, `academic`, `health`
and `imageProfile`, are references to other records rather than inline objects.
Scan image strings are also referenced, using length-delimited text records.
Resolving only newline-delimited JSON is insufficient for this page.

The document list contains `type`, `label`, `imageUrl`, `required`, `uploadedAt`,
`isMultiAttachment`, `recordId`, `sizeBytes`, `review`, `editable`, `locked` and
`lockedReason`. `type` supplies the preview's selection key. Present scan sources
were embedded `data:image/jpeg;base64,...` and `data:image/png;base64,...`; absent
sources were null. The new reader retains only document metadata in profile state.
It resolves the selected image source in a fresh read when the student opens it.

`PortalFlightReader` handles JSON data references, reference property paths, escaped
dollar strings and UTF-8 byte-length `T` text records. It does not execute scripts,
instantiate React components or implement the complete Flight runtime. Missing and
cyclic references do not become fake display strings. The wire format was checked
against [React's primary client implementation](https://github.com/facebook/react/blob/main/packages/react-client/src/ReactFlightClient.js).

## Request and storage boundaries

The preview first resolves the exact selected Portal session, or its captured
current-Moodle SSO link. Embedded images require only the profile GET. An HTTPS
image URL on the exact Portal origin can use the existing authenticated transport;
external origins and unsafe redirects are rejected before cookies are attached.
The viewer makes no write requests and does not expose upload or edit controls.

Source strings exist only while parsing. The selected decoded image stays in
screen memory until the viewer closes, the account changes, or the profile closes
or refreshes. There are no scan files, database entries, external viewer intents,
exports or automatic image prefetches. The page itself already embeds available
scan payloads; this feature cannot prevent the server from including them in the
ordinary profile response.

The authorized private profile passed the production parser and embedded scan
decoder through the opt-in local test. Committed tests use synthetic documents and
exercise references, UTF-8 lengths, missing images, disallowed origins, corrupt
data, account removal, stale responses, retry, zoom gestures and Android Back.
Private captures, browser sessions and account-specific research scripts are
removed after validation. No private image is used in a screenshot or fixture.
