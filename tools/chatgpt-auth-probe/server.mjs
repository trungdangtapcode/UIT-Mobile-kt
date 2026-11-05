import http from 'node:http';
import { readFile, mkdtemp, writeFile, chmod, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BrowserFlow, ISSUER, jsonRequest, checkCatalog, random, equal, ProbeError, safeDiagnostic } from './auth.mjs';
import { CodexDevice } from './codex-device.mjs';

const assets = new Map([['/', ['index.html', 'text/html']], ['/app.js', ['app.js', 'text/javascript']], ['/style.css', ['style.css', 'text/css']]]);
const security = {
  'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};

export class Probe {
  constructor(redirect, { browserOptions, codexBinary = 'codex', deviceFactory, fetcher = fetch } = {}) {
    this.redirect = redirect; this.browserOptions = browserOptions; this.codexBinary = codexBinary;
    this.deviceFactory = deviceFactory || (() => new CodexDevice(codexBinary));
    this.fetcher = fetcher; this.browser = new BrowserFlow(redirect, browserOptions);
    this.device = this.deviceFactory(); this.checks = { browser: {}, device: {} };
    this.discovery = { phase: 'not_checked' }; this.checking = new Set(); this.generation = 0;
  }
  async preflight() {
    try {
      const { body, status } = await jsonRequest(`${ISSUER}/.well-known/openid-configuration`, {}, 262144, this.fetcher);
      this.discovery = { phase: 'checked', status, issuerMatches: body.issuer === ISSUER,
        grants: Array.isArray(body.grant_types_supported) ? body.grant_types_supported.filter(x => ['authorization_code', 'refresh_token', 'urn:ietf:params:oauth:grant-type:device_code'].includes(x)) : [],
        deviceEndpointPublished: typeof body.device_authorization_endpoint === 'string', checkedAt: new Date().toISOString() };
    } catch (error) { this.discovery = { phase: 'failed', diagnostic: safeDiagnostic(error) }; }
  }
  state({ report = false } = {}) {
    const browser = this.browser.publicView(), device = this.device.publicView();
    if (report) { delete device.userCode; delete device.verificationUrl; }
    return {
      recordedAt: new Date().toISOString(), discovery: this.discovery,
      browser: { ...browser, checks: this.checks.browser, checkingModels: this.checking.has('browser') },
      device: { ...device, checks: this.checks.device, checkingModels: this.checking.has('device') },
      verifiedSubjectsMatch: this.browser.subject && this.device.subject ? this.browser.subject === this.device.subject : null,
      inferencePerformed: false, universityAccountUsed: false,
    };
  }
  async start(method) {
    if (method === 'browser') { this.checks.browser = {}; return { authorizationUrl: this.browser.start() }; }
    if (method !== 'device') throw new ProbeError('unknown_method');
    this.checks.device = {};
    const device = this.device;
    try {
      await device.start();
      if (this.device !== device) throw new ProbeError('cancelled');
    } catch (error) { if (this.device === device) device.fail(error); throw error; }
    return {};
  }
  async check(method) {
    if (!['browser', 'device'].includes(method)) throw new ProbeError('unknown_method');
    const generation = this.generation, flow = this[method];
    const access = flow.credentials?.access;
    if (!access) throw new ProbeError('sign_in_first');
    if (this.checking.has(method)) throw new ProbeError('check_in_progress');
    this.checking.add(method);
    try {
      const publicCatalog = await checkCatalog(access, { fetcher: this.fetcher });
      const checks = { publicResponsesCatalog: publicCatalog };
      if (method === 'device') checks.codexCatalog = await checkCatalog(access, { codex: true, codexAccount: flow.credentials.account, fetcher: this.fetcher });
      if (generation === this.generation && this[method] === flow && flow.credentials?.access === access) this.checks[method] = checks;
    } finally { this.checking.delete(method); }
  }
  async reset() {
    const generation = ++this.generation;
    this.browser.cancel(); this.browser.credentials = null;
    await this.device.close();
    if (generation !== this.generation) return;
    this.browser = new BrowserFlow(this.redirect, this.browserOptions); this.device = this.deviceFactory();
    this.checks = { browser: {}, device: {} }; this.checking.clear();
  }
  async close() { this.generation++; this.browser.cancel(); this.browser.credentials = null; await this.device.close(); }
}

export async function createProbeServer({ port = 8877, key = random(), probeOptions = {} } = {}) {
  let origin, authority, probe;
  const send = (response, status, data, type = 'application/json') => {
    response.writeHead(status, { ...security, 'Content-Type': `${type}; charset=utf-8` });
    response.end(type === 'application/json' ? JSON.stringify(data) : data);
  };
  const server = http.createServer(async (request, response) => {
    try {
      if (request.headers.host !== authority) { send(response, 403, { code: 'invalid_host' }); return; }
      const url = new URL(request.url, origin);
      if (url.pathname === '/auth/callback' && request.method === 'GET') {
        try {
          await probe.browser.callback(url.search);
          send(response, 200, '<!doctype html><meta name="viewport" content="width=device-width"><title>OpenAI sign-in returned</title><h1>Sign-in processed</h1><p>Close this tab and return to the original Auth Probe page to inspect the result. No token is displayed here.</p>', 'text/html');
        } catch {
          send(response, 400, '<!doctype html><meta name="viewport" content="width=device-width"><title>Sign-in not completed</title><h1>Sign-in not completed</h1><p>Return to the original Auth Probe page for a safe diagnostic. Start a fresh attempt if the callback expired.</p>', 'text/html');
        }
        return;
      }
      if (request.method === 'GET' && assets.has(url.pathname)) {
        const [file, type] = assets.get(url.pathname);
        send(response, 200, await readFile(new URL(file, import.meta.url), 'utf8'), type); return;
      }
      if (!url.pathname.startsWith('/api/')) { send(response, 404, { code: 'not_found' }); return; }
      if (!equal(request.headers['x-probe-key'], key)) { send(response, 403, { code: 'open_private_launch_url' }); return; }
      if (request.headers.origin && request.headers.origin !== origin) { send(response, 403, { code: 'invalid_origin' }); return; }
      if (request.method === 'GET' && url.pathname === '/api/state') { send(response, 200, probe.state()); return; }
      if (request.method === 'GET' && url.pathname === '/api/report') { send(response, 200, probe.state({ report: true })); return; }
      if (request.method !== 'POST' || request.headers.origin !== origin || request.headers['content-type'] !== 'application/json') { send(response, 403, { code: 'same_origin_post_required' }); return; }
      let bytes = 0;
      for await (const chunk of request) { bytes += chunk.length; if (bytes > 2048) throw new ProbeError('request_too_large'); }
      const route = url.pathname;
      if (route === '/api/start/browser') { send(response, 200, await probe.start('browser')); return; }
      if (route === '/api/start/device') { send(response, 200, await probe.start('device')); return; }
      if (route === '/api/check/browser' || route === '/api/check/device') { await probe.check(route.endsWith('/browser') ? 'browser' : 'device'); send(response, 200, {}); return; }
      if (route === '/api/reset') { await probe.reset(); send(response, 200, {}); return; }
      send(response, 404, { code: 'not_found' });
    } catch (error) { if (!response.headersSent) send(response, 400, safeDiagnostic(error)); else response.end(); }
  });
  server.requestTimeout = 45000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  const address = server.address(); authority = `127.0.0.1:${address.port}`; origin = `http://${authority}`;
  probe = new Probe(`${origin}/auth/callback`, probeOptions);
  return { server, probe, key, origin, launchUrl: `${origin}/#${key}`,
    async close() { await probe.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.umask(0o077);
  const port = Number(process.env.AUTH_PROBE_PORT || 8877);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid AUTH_PROBE_PORT');
  const app = await createProbeServer({ port, probeOptions: { codexBinary: process.env.AUTH_PROBE_CODEX || 'codex' } });
  const runtime = await mkdtemp(join(tmpdir(), 'uit-auth-probe-control-'));
  await chmod(runtime, 0o700);
  await writeFile(join(runtime, 'launch.json'), JSON.stringify({ url: app.launchUrl, port }), { mode: 0o600 });
  // This is a local UI control capability, not an OpenAI credential. Never log OAuth traffic.
  console.log(`Auth Probe: ${app.launchUrl}\nPrivate launch file: ${join(runtime, 'launch.json')}\nListening on loopback only. No inference is sent. Automatic stop after 60 minutes.`);
  void app.probe.preflight();
  let stopping = false;
  async function stop() { if (stopping) return; stopping = true; await app.close(); await rm(runtime, { recursive: true, force: true }); process.exit(0); }
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
  setTimeout(stop, 60 * 60 * 1000).unref();
}
