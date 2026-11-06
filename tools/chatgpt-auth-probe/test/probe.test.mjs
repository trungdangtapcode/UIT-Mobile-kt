import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, SignJWT, exportJWK, createLocalJWKSet } from 'jose';
import { mkdtemp, writeFile, readFile, rm, chmod, stat, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request as httpRequest } from 'node:http';
import { BrowserFlow, ISSUER, RESOURCE, SCOPES, ProbeError, verifyIdentity, inspectDirectGrant, checkCatalog, jsonRequest } from '../auth.mjs';
import { CodexDevice, CODEX_CLIENT_ID } from '../codex-device.mjs';
import { Probe, createProbeServer } from '../server.mjs';
import { lifetimeMinutes, prepareRuntime, writeLaunch, cleanRuntime } from '../runtime.mjs';

const { privateKey, publicKey } = await generateKeyPair('RS256');
const jwk = { ...await exportJWK(publicKey), kid: 'fixture-key', use: 'sig', alg: 'RS256' };
const keys = createLocalJWKSet({ keys: [jwk] });
async function jwt(audience, nonce, extra = {}) {
  return new SignJWT({ ...(nonce ? { nonce } : {}), ...extra }).setProtectedHeader({ alg: 'RS256', kid: 'fixture-key' })
    .setIssuer(extra.iss || ISSUER).setSubject('fixture-subject').setAudience(audience).setIssuedAt().setExpirationTime(extra.exp ?? '1h').sign(privateKey);
}
async function fixtureBrowser({ scope = SCOPES, fetcher: override } = {}) {
  const requests = [];
  let authorization;
  const flow = new BrowserFlow('http://127.0.0.1:8877/auth/callback', { keys, fetcher: override || (async (url, options) => {
    requests.push({ url, options });
    return new Response(JSON.stringify({ id_token: await jwt('oaiapp_fixture', authorization.searchParams.get('nonce')),
      access_token: 'fixture-access-token-not-real', refresh_token: 'fixture-refresh-token-not-real', token_type: 'Bearer', scope, expires_in: 3600 }));
  }) });
  const start = () => { authorization = new URL(flow.start()); return authorization; };
  const callback = () => flow.callback(new URLSearchParams({ state: authorization.searchParams.get('state'), code: 'fixture-code', client_id: 'oaiapp_fixture' }).toString());
  return { flow, start, callback, requests };
}
const waitFor = async predicate => {
  const deadline = Date.now() + 3000;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Fixture timed out'); await new Promise(r => setTimeout(r, 15)); }
};
const rawStatus = (url, headers) => new Promise((resolve, reject) => {
  const request = httpRequest(url, { headers }, response => { response.resume(); resolve(response.statusCode); });
  request.on('error', reject); request.end();
});

test('PKCE exchanges exactly once, verifies identity and reports the granted scopes without credentials', async () => {
  const f = await fixtureBrowser(); const url = f.start();
  assert.equal(url.searchParams.get('client_id'), 'dynamic_agent_client');
  assert.equal(url.searchParams.get('agent_name_hint'), 'UIT Mobile Auth Probe');
  assert.equal(url.searchParams.get('resource'), RESOURCE);
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(url.searchParams.get('code_verifier'), null);
  await f.callback();
  assert.equal(f.requests.length, 1);
  const body = f.requests[0].options.body;
  assert.equal(body.get('grant_type'), 'authorization_code'); assert.equal(body.get('client_id'), 'oaiapp_fixture');
  assert.equal(body.get('redirect_uri'), f.flow.redirect); assert.equal(body.get('resource'), RESOURCE);
  assert.equal(f.flow.publicView().directPermission, true);
  assert.equal(f.flow.publicView().identityVerified, true);
  assert.ok(!JSON.stringify(f.flow.publicView()).includes('fixture-access-token'));
  await assert.rejects(f.callback(), { code: 'invalid_callback_state' });
  const next = f.start();
  assert.equal(next.searchParams.get('client_id'), 'oaiapp_fixture');
  assert.notEqual(next.searchParams.get('code_challenge'), url.searchParams.get('code_challenge'));
  assert.equal(next.searchParams.get('ext_agent_host_id'), url.searchParams.get('ext_agent_host_id'));
});

test('invalid state and duplicate callback parameters cannot consume a valid attempt', async () => {
  const f = await fixtureBrowser(); const url = f.start();
  await assert.rejects(f.flow.callback('state=incorrect&code=unused'), { code: 'invalid_callback_state' });
  await assert.rejects(f.flow.callback(`state=${url.searchParams.get('state')}&state=duplicate`), { code: 'duplicate_callback_parameter' });
  assert.equal(f.requests.length, 0); await f.callback(); assert.equal(f.flow.publicView().phase, 'authenticated');
});

test('denied consent and identity-only grants never become SIWC permission', async () => {
  const f = await fixtureBrowser({ scope: 'openid profile email' }); const url = f.start();
  await assert.rejects(f.flow.callback(`state=${url.searchParams.get('state')}&error=access_denied`), { code: 'access_denied' });
  assert.equal(f.requests.length, 0); f.start(); await f.callback();
  assert.equal(f.flow.publicView().tokenReceived, true); assert.equal(f.flow.publicView().directPermission, false);
});

test('identity validation rejects wrong audience, nonce, expiry, issuer and signature', async () => {
  const token = await jwt('fixture-client', 'fixture-nonce');
  await verifyIdentity(token, 'fixture-client', 'fixture-nonce', keys);
  for (const [value, audience, nonce] of [[token, 'other-client', 'fixture-nonce'], [token, 'fixture-client', 'other'], [await jwt('fixture-client', 'fixture-nonce', { exp: 1 }), 'fixture-client', 'fixture-nonce'], [await jwt('fixture-client', 'fixture-nonce', { iss: 'https://example.test' }), 'fixture-client', 'fixture-nonce'], [token.slice(0, -8) + 'tampered', 'fixture-client', 'fixture-nonce']]) {
    await assert.rejects(verifyIdentity(value, audience, nonce, keys), { code: 'identity_validation_failed' });
  }
});

test('canceling a pending token exchange fences the late result and preserves a new attempt', async () => {
  let release;
  const response = new Promise(resolve => { release = resolve; });
  const f = await fixtureBrowser({ fetcher: () => response }); f.start();
  const pending = f.callback(); f.start();
  release(new Response('{}'));
  await assert.rejects(pending, { code: 'cancelled' });
  assert.equal(f.flow.publicView().phase, 'waiting_for_browser'); assert.equal(f.flow.credentials, null);
});

test('only a signed access claim with the right audience and direct scopes establishes direct permission', async () => {
  assert.equal((await inspectDirectGrant(await jwt(RESOURCE, null, { scope: SCOPES }), keys)).directPermission, true);
  assert.equal((await inspectDirectGrant(await jwt(RESOURCE, null, { scope: 'openid profile' }), keys)).directPermission, false);
  assert.equal((await inspectDirectGrant(await jwt('wrong-audience', null, { scope: SCOPES }), keys)).verified, false);
  assert.equal((await inspectDirectGrant('opaque-fixture-access-token', keys)).verified, false);
});

test('catalog probes are fixed read-only GETs and preserve large catalog and HTTP diagnostic evidence', async () => {
  const urls = [];
  const fetcher = async (url, options) => { urls.push({ url, options }); return new Response(JSON.stringify({ models: [{ slug: 'fixture', description: 'x'.repeat(400000) }] }), { headers: { 'x-request-id': 'fixture-request' } }); };
  const result = await checkCatalog('fixture-token', { fetcher });
  assert.equal(result.accepted, true); assert.equal(result.modelCount, 1); assert.ok(result.responseBytes > 262144);
  await checkCatalog('fixture-token', { codex: true, codexAccount: 'fixture-workspace', fetcher });
  assert.equal(urls[0].url, `${RESOURCE}/models`); assert.equal(urls[0].options.method, undefined);
  assert.equal(urls[0].options.headers['ChatGPT-Account-ID'], undefined);
  assert.equal(urls[1].options.headers['ChatGPT-Account-ID'], 'fixture-workspace');
  const failed = await checkCatalog('fixture-token', { fetcher: async () => new Response('{"detail":"fixture-sensitive-body"}', { status: 403, headers: { 'x-request-id': 'fixture-request' } }) });
  assert.equal(failed.status, 403); assert.equal(failed.bodyShape, 'detail');
  assert.ok(!JSON.stringify(failed).includes('sensitive'));
});

test('HTTP JSON bounds and untrusted error text never leak through diagnostics', async () => {
  await assert.rejects(jsonRequest('https://example.test', {}, 5, async () => new Response('longer than bound')), { code: 'response_too_large' });
  await assert.rejects(jsonRequest('https://example.test', {}, 100, async () => new Response('<html>not json</html>')), { code: 'invalid_json_response' });
  await assert.rejects(jsonRequest('https://example.test', {}, 100, async () => new Response('{"error":"fixture-secret-text"}', { status: 400 })), { code: 'upstream_error' });
});

test('localhost API rejects missing capability, foreign Origin and Host while keeping reports clean', async () => {
  const app = await createProbeServer({ port: 0 });
  try {
    assert.equal((await fetch(`${app.origin}/api/state`)).status, 403);
    const headers = { 'X-Probe-Key': app.key, 'Content-Type': 'application/json', Origin: app.origin };
    assert.equal((await fetch(`${app.origin}/api/reset`, { method: 'POST', headers: { ...headers, Origin: 'https://example.test' }, body: '{}' })).status, 403);
    assert.equal(await rawStatus(`${app.origin}/api/state`, { ...headers, Host: 'example.test' }), 403);
    const report = await fetch(`${app.origin}/api/report`, { headers }); assert.equal(report.status, 200);
    assert.equal(report.headers.get('cache-control'), 'no-store');
    assert.equal((await report.json()).inferencePerformed, false);
    assert.equal(await rawStatus(`${app.origin}/`, { Host: 'example.test' }), 403);
    assert.equal(await rawStatus(`${app.origin}/`, { Host: `localhost:${new URL(app.origin).port}` }), 303);
    assert.equal((await fetch(app.origin, { method: 'HEAD' })).status, 200);
  } finally { await app.close(); }
});

test('probe stays available by default and a finite lifetime is explicit and validated', () => {
  assert.equal(lifetimeMinutes(), 0);
  assert.equal(lifetimeMinutes('60'), 60);
  for (const value of ['-1', 'invalid', '1.5', '10081']) assert.throws(() => lifetimeMinutes(value));
});

test('persistent runtime retains the private UI key across clean service restarts without persisting OAuth credentials', async () => {
  const root = await mkdtemp(join(tmpdir(), 'uit-runtime-test-'));
  try {
    const first = await prepareRuntime(join(root, 'state'));
    await writeLaunch(first, `http://127.0.0.1:8877/#${first.key}`, 8877, 0);
    assert.equal((await stat(join(first.path, 'control-key'))).mode & 0o077, 0);
    const info = JSON.parse(await readFile(join(first.path, 'launch.json'), 'utf8'));
    assert.equal(info.autoStopMinutes, 0);
    assert.deepEqual(Object.keys(info).sort(), ['autoStopMinutes', 'pid', 'port', 'url']);
    await cleanRuntime(first);
    await assert.rejects(readFile(join(first.path, 'launch.json')));
    const second = await prepareRuntime(first.path);
    assert.equal(second.key, first.key);
    await cleanRuntime(second);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('runtime refuses shared directories, shared control keys and symlinked control keys', async () => {
  const root = await mkdtemp(join(tmpdir(), 'uit-runtime-test-'));
  try {
    const directory = join(root, 'state');
    await prepareRuntime(directory);
    await chmod(directory, 0o755);
    await assert.rejects(prepareRuntime(directory));
    await chmod(directory, 0o700);
    await chmod(join(directory, 'control-key'), 0o644);
    await assert.rejects(prepareRuntime(directory));
    await rm(join(directory, 'control-key'));
    await writeFile(join(root, 'outside'), 'fixture', { mode: 0o600 });
    await symlink(join(root, 'outside'), join(directory, 'control-key'));
    await assert.rejects(prepareRuntime(directory));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('safe report omits device code and verification URL, and no catalog is fetched before explicit check', async () => {
  const fake = { publicView: () => ({ phase: 'waiting_for_device_approval', userCode: 'ABCD-1234', verificationUrl: 'https://auth.openai.com/codex/device' }), close: async () => {} };
  const p = new Probe('http://127.0.0.1:8877/auth/callback', { deviceFactory: () => fake });
  assert.ok(p.state().device.userCode);
  assert.equal(p.state({ report: true }).device.userCode, undefined);
  assert.equal(p.state({ report: true }).device.verificationUrl, undefined);
  assert.deepEqual(p.state().device.checks, {});
  await assert.rejects(p.check('browser'), { code: 'sign_in_first' }); await p.close();
});

test('reset during device startup cannot fail the replacement comparison', async () => {
  let rejectStartup;
  const make = () => {
    const view = { phase: 'idle' };
    return { publicView: () => view, close: async () => {}, fail: () => { view.phase = 'failed'; },
      start: () => new Promise((resolve, reject) => { rejectStartup = reject; }) };
  };
  const probe = new Probe('http://127.0.0.1:8877/auth/callback', { deviceFactory: make });
  const start = probe.start('device');
  await probe.reset();
  rejectStartup(new ProbeError('cancelled'));
  await assert.rejects(start, { code: 'cancelled' });
  assert.equal(probe.state().device.phase, 'idle');
  await probe.close();
});

test('actual child-process RPC fixture uses isolated credentials and validates device-issued identity and scope', async () => {
  const fixture = await mkdtemp(join(tmpdir(), 'uit-auth-fixture-'));
  const saved = { tokens: { id_token: await jwt(CODEX_CLIENT_ID), access_token: await jwt(RESOURCE, null, { scope: SCOPES }), refresh_token: 'fixture-only', account_id: 'fixture-workspace' } };
  await writeFile(join(fixture, 'tokens.json'), JSON.stringify(saved), { mode: 0o600 });
  const script = join(fixture, 'fake-codex');
  await writeFile(script, `#!/usr/bin/env node
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline');
const send=x=>process.stdout.write(JSON.stringify(x)+'\\n');
readline.createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line);
 if(m.method==='initialize')send({id:m.id,result:{}});
 if(m.method==='account/login/start'){
  if(process.env.OPENAI_API_KEY||process.env.CODEX_THREAD_ID||process.env.HOME!==process.env.CODEX_HOME)process.exit(9);
  fs.writeFileSync(path.join(process.env.CODEX_HOME,'auth.json'),fs.readFileSync(path.join(__dirname,'tokens.json')),{mode:0o600});
  send({id:m.id,result:{type:'chatgptDeviceCode',loginId:'fixture-login',verificationUrl:'https://auth.openai.com/codex/device',userCode:'ABCD-1234'}});
  setTimeout(()=>send({method:'account/login/completed',params:{loginId:'fixture-login',success:true}}),30);
 }
});
`, { mode: 0o700 });
  const prior = process.env.OPENAI_API_KEY; process.env.OPENAI_API_KEY = 'fixture-never-forward';
  const device = new CodexDevice(script, { keys });
  try {
    await device.start(); await waitFor(() => ['authenticated', 'failed'].includes(device.publicView().phase));
    assert.equal(device.publicView().phase, 'authenticated'); assert.equal(device.publicView().directPermission, true);
    assert.equal(device.publicView().identityVerified, true); assert.equal(device.publicView().userCode, undefined);
    assert.ok(!JSON.stringify(device.publicView()).includes('fixture-only'));
    await waitFor(() => device.child.exitCode !== null || device.child.signalCode !== null);
    await waitFor(() => device.view.phase === 'authenticated');
    await assert.rejects(device.rpc('turn/start', {}), { code: 'rpc_not_allowed' });
    const home = device.home; await device.close(); await assert.rejects(readFile(join(home, 'auth.json')));
  } finally {
    if (prior === undefined) delete process.env.OPENAI_API_KEY; else process.env.OPENAI_API_KEY = prior;
    await device.close(); await rm(fixture, { recursive: true, force: true });
  }
});
