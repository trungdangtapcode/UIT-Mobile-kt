# ChatGPT authentication probe

A small loopback-only HTML test tool for comparing two real authorization paths:

1. **SIWC browser PKCE:** a new dynamic client named UIT Mobile Auth Probe,
   an exact loopback callback, code exchange, signed ID-token validation and the
   actually granted `chatgpt.tokens.use.direct` / `resource.invoke` scopes.
2. **Codex device code:** the documented app-server device flow in a fresh,
   temporary `CODEX_HOME`. This creates no new UIT-specific SIWC registration.
   The tool verifies the ID token and, when possible, signed direct-permission
   claims in the access token. It does not assume Codex credentials are SIWC.

The owner completes authentication and consent on OpenAI's page. No credentials
from the normal Codex installation, `.env`, API keys or university accounts are
read or reused. No model inference, posting or other university action is sent.

## Run on the Linux SSH host

Requires Node.js 22+ and an installed Codex executable for the device-code method.
The owner running the HTML page needs only a browser. This is a development test
harness; it does not add a backend requirement to the native Android application.

```sh
cd tools/chatgpt-auth-probe
npm ci --ignore-scripts --no-audit --no-fund
npm start
```

The server prints a private launch URL like
`http://127.0.0.1:8877/#<random-local-control-key>`. It binds IPv4 loopback only,
validates Host and Origin, and requires the control key for its API. Do not expose
this authentication harness on a public interface. There is no automatic shutdown
by default. Set `AUTH_PROBE_TTL_MINUTES=60` for an explicit one-hour test window;
`0` keeps it running until stopped. Access-token expiry is independent of server
availability.

### Keep it running across SSH disconnects

On a Linux host with a working systemd user manager, start it as a user service
from this directory. This does not require root or install a system service:

```sh
systemd-run --user --unit=uit-chatgpt-auth-probe \
  --working-directory="$PWD" \
  --property=UMask=0077 --property=Restart=on-failure --property=RestartSec=3 \
  --setenv=AUTH_PROBE_STATE_DIR="$HOME/.local/state/uit-mobile-auth-probe" \
  --setenv=AUTH_PROBE_CODEX="$(command -v codex)" \
  "$(command -v node)" "$PWD/server.mjs"
```

The private state directory stores only a stable local UI control key and launch
metadata. It does not store OAuth tokens. Reusing that key preserves the launch
URL across process restarts; active authorization attempts and credentials remain
in memory and must be restarted after a process restart. The directory must be
owned by the current user with mode `0700`, and the key file uses mode `0600`.
The user service is transient and is not enabled automatically at host boot.
For operation after all login sessions end, the host must already support user
lingering; the probe does not change that system setting.

```sh
systemctl --user status uit-chatgpt-auth-probe
cat "$HOME/.local/state/uit-mobile-auth-probe/launch.json"
curl -fsSL http://localhost:8877/ -o /dev/null -w 'HTTP %{http_code}\n'
```

Stop it explicitly when finished:

```sh
systemctl --user stop uit-chatgpt-auth-probe
```

`localhost` page requests redirect to the canonical IPv4 address `127.0.0.1`;
foreign Host and Origin values remain rejected. Public static pages support HEAD
requests. Open the current launch URL, including its fragment, after restarting an
old temporary server. A normal foreground `npm start` still ends when its terminal
session ends unless it is managed separately.

In a new Windows terminal, reuse the SSH destination/options you already use,
adding this forwarding option (replace `user@server` with that destination):

```sh
ssh -N -L 8877:127.0.0.1:8877 user@server
```

Open the full printed launch URL in the Windows browser. Both forwarded ports
must match: OpenAI returns PKCE to that same `127.0.0.1` port in the browser.
Do not open `index.html` as a file. The small Node server owns transaction state,
token exchange and validation, avoiding browser CORS and token exposure.

Use `AUTH_PROBE_PORT` to change the port on both ends and `AUTH_PROBE_CODEX` to
select a trusted installed executable. For example, on the Linux host:

```sh
AUTH_PROBE_PORT=8878 AUTH_PROBE_CODEX=/home/your-user/.local/bin/codex npm start
```

## Compare the methods

- Browser: Continue with ChatGPT first prepares a valid authorization URL, then
  opens OpenAI directly. No blank placeholder tab is created or closed. If the
  browser blocks automatic opening, use the visible Open OpenAI sign-in link.
  Complete authorization in the new tab, close the generic callback tab and
  inspect the original page. Controls remain disabled until the local launch
  capability is accepted. Errors appear above the methods and receive focus.
  Retry uses the
  same issued client ID during the current comparison. A new attempt has fresh
  state, nonce and S256 PKCE. The listener rejects duplicate/expired callbacks.
- Device: Get a device code, open the official verification link, enter the code
  and approve with the intended account. The app-server handles bounded polling.
  Device code may need to be enabled in account/workspace security settings.
- **Check API access** explicitly reads the public `/v1/models` catalog. For the
  Codex method it also reads the Codex backend catalog with the appropriate
  account header. It never sends SIWC credentials to the Codex backend.
- **Clear local test & switch account** cancels attempts and drops temporary
  local credentials. Use it before testing the other account. This does not
  revoke an existing OpenAI app connection; manage that in ChatGPT settings.
- Download safe report excludes all access/refresh/ID tokens, subjects, email,
  authorization URLs, device codes and raw upstream error text. It retains
  boolean checks, allowed scopes, safe error codes, HTTP status, request ID,
  response size/shape and model counts. Status `0` means no HTTP response status
  was captured. Reports do not include model instructions or other catalog data.

## What the results establish

Token received, verified identity, direct plan permission, accepted catalog and
completed inference are separate facts. The probe displays each available fact.
It does not perform inference, so a successful catalog is not proof that a paid
or plan-backed generation will complete.

For device code, `Direct SIWC permission: Verified` requires an access JWT with
a valid OpenAI signature, issuer, resource audience, lifetime and both direct
permission scopes. Failure to verify those claims means **not established**, not
proof the credential is invalid for Codex. A Codex-issued credential still belongs
to that Codex client; this is not a new UIT Mobile client registration. The
separate catalog results can reveal different admission/response contracts.

The public discovery panel shows whether a standard device authorization endpoint
is advertised. Its absence does not prove that no private device flow exists.
This tool uses the documented Codex RPC rather than inventing a SIWC device API.

## Credential lifecycle

SIWC credentials remain in server memory until reset or shutdown; no browser
storage or report receives them. The browser stores only the local UI control
capability in session storage. The host ID and issued registration are retained
only during the running comparison; a new comparison/restart is a fresh test host.
This deliberately does not replace the Android app's persistent encrypted profile.

Codex runs with an allowlisted environment and fresh private temporary HOME and
CODEX_HOME directories. File credential storage is explicit; keyring fallback is
not used. ID tokens are validated against the public Codex client ID from upstream
0.166.0. After successful validation the child process stops and its credential
directory is removed; the access token remains in probe memory for explicit
read-only checks. Reset and shutdown also remove the temporary profile. Abrupt
termination before cleanup can leave a private `uit-auth-probe-codex-*` directory
under the OS temporary directory. Remove only this probe's leftover directory.

No token renewal is attempted. Reauthorize if the short-lived access token expires.
Normal server output contains only its launch URL/control-file path, never OAuth
requests, callbacks, tokens or app-server output. The private launch file is
removed on normal shutdown. With `AUTH_PROBE_STATE_DIR`, the non-OAuth local UI
key remains for the next start; otherwise its temporary directory is removed. No API is exposed to export raw tokens.

## Local validation

```sh
npm test
```

The optional browser regression requires a separately installed Puppeteer module
and Chromium (not needed to use the auth page):

```sh
AUTH_PROBE_PUPPETEER_MODULE=/path/to/puppeteer/lib/puppeteer/puppeteer.js \
PUPPETEER_EXECUTABLE_PATH=/path/to/chrome npm run test:browser
```

It checks stale links, switching to a new launch fragment in an existing tab,
rejected requests without blank popups, visible errors, actual provider-tab
navigation and an ordinary-link fallback when popup blocking is simulated.
The token callback portion uses a signed fixture and is explicitly separate from
real subscriber acceptance. The test returns focus to the original tab as a user
would after authorization. Published connected-account evidence is not generated.

A page already open during a script update should be reloaded. Subsequently,
changing its launch fragment is handled without retaining the previous local key.

Tests use signed fixture credentials and an isolated child-process RPC fixture.
They cover PKCE, callback replay/state checks, nonce/audience/issuer/signature/time,
denied grants, cancellation fencing, response bounds, safe errors, Host/Origin/
control-key guards, report redaction and environment/profile isolation.

During development the actual installed Codex 0.166.0 returned a fresh device
code from OpenAI. That attempt was cancelled before any account authenticated.
The actual HTML was exercised in Chromium at 390px and 900px; no subscriber
credentials or generated answers were substituted into the page. Owner sign-in
and real token/catalog acceptance still require the owner's browser approval.

## Official references

- [SIWC registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [SIWC token claims](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)
- [SIWC model catalog and inference contract](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Codex device-code RPC](https://learn.chatgpt.com/docs/app-server#3b-log-in-with-chatgpt-device-code-flow)
- [Codex client ID source](https://github.com/openai/codex/blob/rust-v0.166.0/codex-rs/login/src/auth/manager.rs)
- [Codex credential-file contract](https://github.com/openai/codex/blob/rust-v0.166.0/codex-rs/login/src/auth/storage.rs)
