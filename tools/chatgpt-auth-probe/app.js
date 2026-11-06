const $ = id => document.getElementById(id);
let key;
function readLaunchKey() {
  const fragment = location.hash.slice(1);
  if (fragment) { sessionStorage.setItem('uit-auth-probe-control', fragment); history.replaceState(null, '', location.pathname); }
  key = sessionStorage.getItem('uit-auth-probe-control') || '';
}
readLaunchKey();
let connected = false, browserStarting = false;
async function api(path, post = false) {
  const response = await fetch(`/api/${path}`, { method: post ? 'POST' : 'GET', cache: 'no-store',
    headers: { 'X-Probe-Key': key, ...(post ? { 'Content-Type': 'application/json' } : {}) }, ...(post ? { body: '{}' } : {}) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.code || 'local_request_failed');
  return result;
}
function failure(error) {
  const detail = error.message === 'open_private_launch_url'
    ? 'Open the current full private launch URL, including its fragment, then try again.'
    : error.message === 'invalid_origin' || error.message === 'same_origin_post_required'
      ? 'Open the test through its 127.0.0.1 launch URL and SSH tunnel, then reload.'
      : 'Check the local server and retry. The existing app and normal Codex profiles are unchanged.';
  $('error').hidden = false; $('error').textContent = `Could not complete this action (${error.message}). ${detail}`;
  $('error').scrollIntoView({ block: 'center' }); $('error').focus({ preventScroll: true });
}
function row(dl, label, value) { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = value; dl.append(dt, dd); }
function renderResult(id, state, method) {
  const root = $(id); root.replaceChildren(); const dl = document.createElement('dl');
  row(dl, 'Step', state.phase.replaceAll('_', ' '));
  row(dl, 'Access token received', state.tokenReceived ? 'Yes · kept on server' : 'Not yet');
  row(dl, 'Identity signature verified', state.identityVerified ? 'Yes' : 'Not yet');
  row(dl, 'Direct SIWC permission', state.directPermission ? 'Verified' : state.tokenReceived ? 'Not established' : 'Not yet');
  if (method === 'device' && state.tokenReceived) row(dl, 'Access-token claims verified', state.accessClaimsVerified ? 'Yes' : 'Not established');
  if (state.scopes?.length) row(dl, 'Reported / verified scopes', state.scopes.join(' '));
  if (state.expiresIn) row(dl, 'Lifetime at issue', `${state.expiresIn} seconds`);
  if (state.tokenReceived) row(dl, 'Refresh token received', state.refreshTokenReceived ? 'Yes' : 'No');
  root.append(dl);
  if (state.diagnostic) { const pre = document.createElement('pre'); pre.textContent = JSON.stringify(state.diagnostic, null, 2); root.append(pre); }
  if (state.checkingModels) { const p = document.createElement('p'); p.textContent = 'Reading model catalogs…'; root.append(p); }
  for (const [name, result] of Object.entries(state.checks || {})) {
    const h = document.createElement('h3'); h.textContent = name === 'codexCatalog' ? 'Codex backend catalog' : 'Public Responses model catalog';
    const pre = document.createElement('pre'); pre.textContent = JSON.stringify(result, null, 2); root.append(h, pre);
  }
}
async function refresh() {
  try {
    const state = await api('state');
    connected = true;
    $('connection').textContent = 'Connected to your local test server. Tokens stay out of the browser.';
    renderResult('browser-result', state.browser, 'browser'); renderResult('device-result', state.device, 'device');
    $('check-browser').disabled = !state.browser.tokenReceived || state.browser.checkingModels;
    $('check-device').disabled = !state.device.tokenReceived || state.device.checkingModels;
    $('start-browser').disabled = browserStarting;
    $('start-device').disabled = state.device.phase !== 'idle';
    $('reset').disabled = false; $('report').disabled = false;
    $('device-approval').hidden = !state.device.userCode;
    $('device-code').textContent = state.device.userCode || '';
    if (state.device.verificationUrl) $('device-link').href = state.device.verificationUrl;
    $('discovery').textContent = JSON.stringify(state.discovery, null, 2);
    $('identity-comparison').textContent = state.verifiedSubjectsMatch === null ? 'Complete both methods to compare verified subjects.' : `Verified token subjects match: ${state.verifiedSubjectsMatch ? 'yes' : 'no'}. Client registrations remain separate.`;
  } catch (error) {
    connected = false;
    for (const id of ['start-browser', 'start-device', 'check-browser', 'check-device', 'report', 'reset']) $(id).disabled = true;
    $('connection').textContent = error.message === 'open_private_launch_url'
      ? 'This launch link is missing or belongs to an older test server. Open the current full private launch URL, including its fragment.'
      : 'Local server unavailable. Check your SSH tunnel and user service.';
  }
}
$('start-browser').onclick = async () => {
  if (!connected || browserStarting) return;
  browserStarting = true; $('start-browser').disabled = true;
  $('browser-link').hidden = true; $('browser-link').removeAttribute('href');
  $('browser-action').textContent = 'Preparing your OpenAI sign-in link…';
  try {
    $('error').hidden = true; const result = await api('start/browser', true);
    const url = new URL(result.authorizationUrl);
    if (url.origin !== 'https://auth.openai.com' || url.pathname !== '/api/accounts/authorize') throw new Error('invalid_authorization_url');
    $('browser-link').href = url.href; $('browser-link').hidden = false;
    $('browser-action').textContent = 'Your sign-in link is ready. If no tab opens, select Open OpenAI sign-in above.';
    // Open only a prepared provider URL. Never create or close a blank placeholder tab.
    try { window.open(url.href, '_blank', 'noopener,noreferrer'); } catch { /* The ordinary link remains available when popups are blocked. */ }
    await refresh();
  } catch (error) { $('browser-action').textContent = 'No sign-in link was created.'; failure(error); }
  finally { browserStarting = false; $('start-browser').disabled = !connected; }
};
$('start-device').onclick = async () => {
  $('start-device').disabled = true; $('error').hidden = true;
  try { await api('start/device', true); } catch (error) { failure(error); } finally { await refresh(); }
};
for (const method of ['browser', 'device']) $(`check-${method}`).onclick = async () => {
  $(`check-${method}`).disabled = true; $('error').hidden = true;
  try { await api(`check/${method}`, true); } catch (error) { failure(error); } finally { await refresh(); }
};
$('copy-code').onclick = async () => {
  try { await navigator.clipboard.writeText($('device-code').textContent); $('copy-code').textContent = 'Copied'; setTimeout(() => { $('copy-code').textContent = 'Copy code'; }, 2000); } catch { $('copy-code').textContent = 'Select the code to copy'; }
};
$('reset').onclick = async () => {
  $('reset').disabled = true; $('error').hidden = true;
  try { await api('reset', true); $('browser-link').hidden = true; await refresh(); } catch (error) { failure(error); } finally { $('reset').disabled = false; }
};
$('report').onclick = async () => {
  try {
    const report = await api('report'); const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = 'chatgpt-auth-probe-report.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) { failure(error); }
};
window.addEventListener('hashchange', () => { readLaunchKey(); $('error').hidden = true; void refresh(); });
await refresh();
setInterval(refresh, 2000);
