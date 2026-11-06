const $ = id => document.getElementById(id);
const fragment = location.hash.slice(1);
if (fragment) { sessionStorage.setItem('uit-auth-probe-control', fragment); history.replaceState(null, '', location.pathname); }
const key = sessionStorage.getItem('uit-auth-probe-control') || '';
async function api(path, post = false) {
  const response = await fetch(`/api/${path}`, { method: post ? 'POST' : 'GET', cache: 'no-store',
    headers: { 'X-Probe-Key': key, ...(post ? { 'Content-Type': 'application/json' } : {}) }, ...(post ? { body: '{}' } : {}) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.code || 'local_request_failed');
  return result;
}
function failure(error) { $('error').hidden = false; $('error').textContent = `Test could not complete: ${error.message}. Your existing app and Codex profiles are unchanged.`; }
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
    $('connection').textContent = 'Connected to your local test server. Tokens stay out of the browser.';
    renderResult('browser-result', state.browser, 'browser'); renderResult('device-result', state.device, 'device');
    $('check-browser').disabled = !state.browser.tokenReceived || state.browser.checkingModels;
    $('check-device').disabled = !state.device.tokenReceived || state.device.checkingModels;
    $('start-device').disabled = state.device.phase !== 'idle';
    $('device-approval').hidden = !state.device.userCode;
    $('device-code').textContent = state.device.userCode || '';
    if (state.device.verificationUrl) $('device-link').href = state.device.verificationUrl;
    $('discovery').textContent = JSON.stringify(state.discovery, null, 2);
    $('identity-comparison').textContent = state.verifiedSubjectsMatch === null ? 'Complete both methods to compare verified subjects.' : `Verified token subjects match: ${state.verifiedSubjectsMatch ? 'yes' : 'no'}. Client registrations remain separate.`;
  } catch (error) {
    $('connection').textContent = error.message === 'open_private_launch_url'
      ? 'This launch link is missing or belongs to an older test server. Open the current full private launch URL, including its fragment.'
      : 'Local server unavailable. Check your SSH tunnel and user service.';
  }
}
$('start-browser').onclick = async () => {
  const popup = window.open('about:blank', '_blank'); if (popup) popup.opener = null;
  try {
    $('error').hidden = true; const result = await api('start/browser', true);
    $('browser-link').href = result.authorizationUrl; $('browser-link').hidden = false;
    if (popup) popup.location = result.authorizationUrl;
    await refresh();
  } catch (error) { popup?.close(); failure(error); }
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
await refresh();
setInterval(refresh, 2000);
