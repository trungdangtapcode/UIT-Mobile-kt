/** Manual Chromium regression. Provider navigation is real; callback identity below is a signed fixture. */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { createProbeServer } from '../server.mjs';
import { ISSUER, SCOPES } from '../auth.mjs';

const { default: puppeteer } = await import(process.env.AUTH_PROBE_PUPPETEER_MODULE || 'puppeteer');
const output = process.env.AUTH_PROBE_BROWSER_OUTPUT || join(tmpdir(), 'uit-auth-probe-browser');
await mkdir(output, { recursive: true });
const { privateKey, publicKey } = await generateKeyPair('RS256');
const jwk = { ...await exportJWK(publicKey), kid: 'browser-fixture-key', use: 'sig', alg: 'RS256' };
let nonce;
const app = await createProbeServer({ port: 0, probeOptions: { browserOptions: {
  keys: createLocalJWKSet({ keys: [jwk] }),
  fetcher: async () => new Response(JSON.stringify({
    id_token: await new SignJWT({ nonce }).setProtectedHeader({ alg: 'RS256', kid: jwk.kid })
      .setIssuer(ISSUER).setSubject('browser-fixture-subject').setAudience('oaiapp_browser_fixture').setIssuedAt().setExpirationTime('1h').sign(privateKey),
    access_token: 'browser-fixture-access-not-real', refresh_token: 'browser-fixture-refresh-not-real',
    token_type: 'Bearer', expires_in: 3600, scope: SCOPES,
  })),
} } });
const browser = await puppeteer.launch({ headless: true,
  ...(process.env.PUPPETEER_EXECUTABLE_PATH ? { executablePath: process.env.PUPPETEER_EXECUTABLE_PATH } : {}),
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const results = { invalidLinkDisabled: false, rejectedStartOpensNoPopup: false, rejectedStartErrorVisible: false,
  realPopupNavigation: false, controlledCallbackReturnsVerifiedResult: false, blockedPopupManualLinkWorks: false,
  actualSubscriberAuthenticated: false, providerLoginCompleted: false, inferencePerformed: false, fixtureCallbackUsed: true };
const pageErrors = [];
try {
  const page = await browser.newPage();
  page.on('pageerror', error => pageErrors.push(error.name));
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
  await page.goto(`${app.origin}/#outdated-link`, { waitUntil: 'networkidle0' });
  await page.waitForFunction(() => document.querySelector('#connection').textContent.includes('older test server'));
  assert.equal(await page.$eval('#start-browser', node => node.disabled), true);
  assert.equal(await page.$eval('#start-device', node => node.disabled), true);
  results.invalidLinkDisabled = true;

  await page.goto(app.launchUrl, { waitUntil: 'networkidle0' });
  await page.waitForFunction(() => !document.querySelector('#start-browser').disabled);
  await page.screenshot({ path: join(output, 'empty-form-390.png'), fullPage: true });
  let opened = 0;
  const track = target => { if (target.type() === 'page' && target !== page.target()) opened++; };
  browser.on('targetcreated', track);
  let rejectStart = true;
  await page.setRequestInterception(true);
  page.on('request', request => {
    if (rejectStart && request.url() === `${app.origin}/api/start/browser`) {
      return void request.respond({ status: 403, contentType: 'application/json', body: '{"code":"open_private_launch_url"}' });
    }
    void request.continue();
  });
  await page.click('#start-browser');
  await page.waitForFunction(() => !document.querySelector('#error').hidden);
  assert.equal(opened, 0);
  assert.equal(await page.evaluate(() => {
    const r = document.querySelector('#error').getBoundingClientRect();
    return r.top >= 0 && r.bottom <= innerHeight && document.activeElement.id === 'error';
  }), true);
  assert.equal(await page.$eval('#browser-link', node => node.hidden), true);
  results.rejectedStartOpensNoPopup = true; results.rejectedStartErrorVisible = true;
  await page.screenshot({ path: join(output, 'rejected-start-390.png'), fullPage: false });

  rejectStart = false;
  await page.evaluate(() => {
    window.__openedUrls = [];
    const nativeOpen = window.open.bind(window);
    window.open = (...args) => { window.__openedUrls.push(args[0]); return nativeOpen(...args); };
  });
  const providerTarget = browser.waitForTarget(target => target !== page.target() && target.type() === 'page' && target.url().startsWith('https://auth.openai.com/'), { timeout: 20000 });
  await page.click('#start-browser');
  const popup = await (await providerTarget).page();
  const authorization = new URL(await page.$eval('#browser-link', node => node.href));
  assert.equal(authorization.origin, ISSUER);
  assert.equal(authorization.pathname, '/api/accounts/authorize');
  assert.equal(await page.evaluate(() => window.__openedUrls.some(url => url === 'about:blank')), false);
  assert.equal(popup.isClosed(), false);
  results.realPopupNavigation = true;

  // The owner must still complete actual provider login. This callback is a controlled fixture.
  nonce = authorization.searchParams.get('nonce');
  const query = new URLSearchParams({ state: authorization.searchParams.get('state'), code: 'controlled-browser-fixture-code', client_id: 'oaiapp_browser_fixture' });
  const callback = await popup.goto(`${app.origin}/auth/callback?${query}`, { waitUntil: 'domcontentloaded' });
  if (app.probe.browser.publicView().phase !== 'authenticated') throw new Error(JSON.stringify({ callbackStatus: callback.status(), phase: app.probe.browser.publicView().phase, diagnostic: app.probe.browser.publicView().diagnostic }));
  await page.bringToFront();
  await page.waitForFunction(() => document.querySelector('#browser-result').textContent.includes('authenticated'));
  assert.equal(app.probe.browser.publicView().identityVerified, true);
  assert.equal(app.probe.browser.publicView().directPermission, true);
  results.controlledCallbackReturnsVerifiedResult = true;
  await popup.close();
  await app.probe.reset();

  // Explicitly simulate popup blocking, then exercise an ordinary user-clicked link.
  await page.evaluate(() => { window.open = () => null; });
  await page.click('#start-browser');
  await page.waitForFunction(() => !document.querySelector('#browser-link').hidden && !document.querySelector('#start-browser').disabled);
  const manualTarget = browser.waitForTarget(target => target !== page.target() && target.type() === 'page' && target.url().startsWith('https://auth.openai.com/'), { timeout: 20000 });
  await page.click('#browser-link');
  const manual = await (await manualTarget).page();
  assert.equal(manual.isClosed(), false); await manual.close();
  results.blockedPopupManualLinkWorks = true;
  browser.off('targetcreated', track);
  assert.deepEqual(pageErrors, []);
  results.pageErrors = pageErrors;
  await writeFile(join(output, 'verification.json'), JSON.stringify(results, null, 2) + '\n');
  console.log(JSON.stringify(results));
} finally { await browser.close(); await app.close(); }
