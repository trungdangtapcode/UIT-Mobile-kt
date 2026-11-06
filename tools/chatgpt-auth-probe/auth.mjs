import { randomBytes, randomUUID, createHash, timingSafeEqual } from 'node:crypto';
import { createRemoteJWKSet, jwtVerify } from 'jose';

export const ISSUER = 'https://auth.openai.com';
export const RESOURCE = 'https://api.openai.com/v1';
export const SCOPES = 'openid profile email offline_access resource.invoke chatgpt.tokens.use.direct';
export const PLAN_SCOPE = 'chatgpt.tokens.use.direct';
export const CATALOG_LIMIT = 4 * 1024 * 1024;
const remoteKeys = createRemoteJWKSet(new URL(`${ISSUER}/.well-known/jwks.json`), { timeoutDuration: 15000 });
const knownErrors = new Set(['access_denied', 'invalid_grant', 'invalid_client', 'invalid_scope', 'invalid_request', 'invalid_token', 'unsupported_grant_type', 'login_required', 'consent_required', 'account_deactivated', 'subscription_sharing_user_not_eligible', 'subscription_sharing_invalid_user', 'subscription_sharing_route_not_supported', 'subscription_sharing_user_unavailable', 'subscription_sharing_usage_unavailable', 'chatpass_v2_scope_not_authorized', 'chatpass_v2_invalid_authorization_context']);

export class ProbeError extends Error {
  constructor(code, details = {}) { super(code); this.code = code; this.details = details; }
}
export function random() { return randomBytes(32).toString('base64url'); }
export function equal(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a), right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}
export function parameters(search) {
  const result = {};
  for (const [key, value] of new URLSearchParams(search)) {
    if (Object.hasOwn(result, key)) throw new ProbeError('duplicate_callback_parameter');
    result[key] = value;
  }
  return result;
}
export function safeDiagnostic(error) {
  return error instanceof ProbeError ? { code: error.code, ...error.details } : { code: 'unexpected_failure' };
}
function requestId(response) {
  const id = response.headers.get('x-request-id') || '';
  return /^[a-zA-Z0-9._-]{1,160}$/.test(id) ? id : '';
}
export async function jsonRequest(url, options = {}, limit = 262144, fetcher = fetch) {
  let response;
  try {
    response = await fetcher(url, { ...options, redirect: 'error', signal: options.signal || AbortSignal.timeout(20000) });
  } catch (error) {
    if (options.signal?.aborted) throw new ProbeError('cancelled');
    throw new ProbeError(error.name === 'TimeoutError' ? 'request_timeout' : 'network_error', { status: 0 });
  }
  const detail = { status: response.status, requestId: requestId(response) };
  const chunks = [];
  let bytes = 0;
  try {
    for await (const chunk of response.body || []) {
      bytes += chunk.length;
      if (bytes > limit) throw new ProbeError('response_too_large', { ...detail, responseBytes: bytes });
      chunks.push(chunk);
    }
  } catch (error) {
    if (error instanceof ProbeError) throw error;
    throw new ProbeError('interrupted_response', detail);
  }
  let body;
  try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { /* No raw error body is retained. */ }
  if (!response.ok) {
    const candidate = typeof body?.error === 'string' ? body.error : body?.error?.code;
    throw new ProbeError(knownErrors.has(candidate) ? candidate : 'upstream_error', {
      ...detail, responseBytes: bytes,
      bodyShape: body?.error ? 'error' : body?.detail ? 'detail' : body ? 'other-json' : 'non-json',
    });
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new ProbeError('invalid_json_response', { ...detail, responseBytes: bytes });
  return { body, ...detail, responseBytes: bytes };
}

export async function verifyIdentity(token, audience, nonce, keys = remoteKeys) {
  try {
    const { payload } = await jwtVerify(token, keys, { algorithms: ['RS256'], issuer: ISSUER, audience, clockTolerance: 30 });
    if (typeof payload.sub !== 'string' || !payload.sub || !payload.exp || (nonce !== undefined && !equal(payload.nonce, nonce))) throw new Error();
    if ((payload.azp !== undefined || (Array.isArray(payload.aud) && payload.aud.length > 1)) && payload.azp !== audience) throw new Error();
    return { subject: payload.sub };
  } catch { throw new ProbeError('identity_validation_failed'); }
}

/** Only a verified access-token claim can establish direct permission for a Codex-issued token. */
export async function inspectDirectGrant(token, keys = remoteKeys) {
  try {
    const { payload } = await jwtVerify(token, keys, { algorithms: ['RS256'], issuer: ISSUER, audience: RESOURCE, clockTolerance: 30 });
    if (!payload.exp || typeof payload.sub !== 'string' || !payload.sub) throw new Error();
    const scopes = typeof payload.scope === 'string' ? payload.scope.split(' ').filter(Boolean) : [];
    return { verified: true, scopes: scopes.filter(s => SCOPES.split(' ').includes(s)), directPermission: scopes.includes(PLAN_SCOPE) && scopes.includes('resource.invoke') };
  } catch { return { verified: false, scopes: [], directPermission: false }; }
}

export class BrowserFlow {
  constructor(redirect, { fetcher = fetch, keys = remoteKeys } = {}) {
    this.redirect = redirect; this.fetcher = fetcher; this.keys = keys;
    this.hostId = `urn:uuid:${randomUUID()}`;
    this.clientId = null; this.subject = null; this.credentials = null;
    this.attempt = null; this.generation = 0;
    this.view = { phase: 'idle', tokenReceived: false, identityVerified: false, directPermission: false, scopes: [] };
  }
  start() {
    this.cancel();
    const attempt = { state: random(), nonce: random(), verifier: random(), expires: Date.now() + 600000, generation: this.generation };
    this.attempt = attempt; this.credentials = null;
    this.view = { phase: 'waiting_for_browser', tokenReceived: false, identityVerified: false, directPermission: false, scopes: [], startedAt: new Date().toISOString() };
    const values = {
      client_id: this.clientId || 'dynamic_agent_client', ext_agent_host_id: this.hostId,
      response_type: 'code', redirect_uri: this.redirect, scope: SCOPES, resource: RESOURCE,
      state: attempt.state, nonce: attempt.nonce, code_challenge_method: 'S256',
      code_challenge: createHash('sha256').update(attempt.verifier).digest('base64url'),
    };
    if (!this.clientId) values.agent_name_hint = 'UIT Mobile Auth Probe';
    return `${ISSUER}/api/accounts/authorize?${new URLSearchParams(values)}`;
  }
  cancel() { this.generation++; this.attempt = null; }
  async callback(search) {
    const input = parameters(search), attempt = this.attempt;
    if (!attempt || !equal(input.state, attempt.state)) throw new ProbeError('invalid_callback_state');
    this.attempt = null; // Consume once, before awaiting the token endpoint.
    if (attempt.expires < Date.now()) { this.view.phase = 'expired'; throw new ProbeError('authorization_expired'); }
    try {
      if (input.error) throw new ProbeError(input.error === 'access_denied' ? 'access_denied' : 'authorization_rejected');
      const clientId = input.client_id || this.clientId;
      if (!clientId || clientId === 'dynamic_agent_client' || !/^[a-zA-Z0-9_-]{6,200}$/.test(clientId) || (this.clientId && clientId !== this.clientId)) throw new ProbeError('invalid_issued_client');
      this.clientId = clientId; // Retain a registration even if one code exchange fails.
      if (typeof input.code !== 'string' || input.code.length < 1 || input.code.length > 4096) throw new ProbeError('missing_authorization_code');
      this.view.phase = 'exchanging_code';
      const { body } = await jsonRequest(`${ISSUER}/api/accounts/oauth/token`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'authorization_code', client_id: clientId, code: input.code, code_verifier: attempt.verifier, redirect_uri: this.redirect, resource: RESOURCE }),
      }, 262144, this.fetcher);
      if (attempt.generation !== this.generation) throw new ProbeError('cancelled');
      this.view.phase = 'verifying_identity';
      const identity = await verifyIdentity(body.id_token, clientId, attempt.nonce, this.keys);
      if (this.subject && this.subject !== identity.subject) throw new ProbeError('different_account_registration');
      if (body.token_type?.toLowerCase() !== 'bearer' || typeof body.access_token !== 'string' || body.access_token.length < 20 || body.access_token.length > 32768 || !/^[\x21-\x7e]+$/.test(body.access_token) || !Number.isInteger(body.expires_in) || body.expires_in < 1 || body.expires_in > 86400) throw new ProbeError('invalid_token_response');
      if (attempt.generation !== this.generation) throw new ProbeError('cancelled');
      this.subject = identity.subject;
      const scopes = typeof body.scope === 'string' ? body.scope.split(' ').filter(Boolean) : [];
      this.credentials = { access: body.access_token, refresh: typeof body.refresh_token === 'string' ? body.refresh_token : null };
      this.view = {
        phase: 'authenticated', tokenReceived: true, identityVerified: true,
        directPermission: scopes.includes(PLAN_SCOPE) && scopes.includes('resource.invoke'),
        scopes: scopes.filter(s => SCOPES.split(' ').includes(s)),
        refreshTokenReceived: Boolean(this.credentials.refresh),
        expiresIn: Number.isInteger(body.expires_in) && body.expires_in > 0 && body.expires_in <= 86400 ? body.expires_in : null,
        clientRegistration: 'UIT Mobile Auth Probe', completedAt: new Date().toISOString(),
      };
    } catch (error) {
      if (attempt.generation === this.generation) this.view = { ...this.view, phase: 'failed', diagnostic: safeDiagnostic(error) };
      throw error;
    }
  }
  publicView() {
    if (this.attempt && this.attempt.expires < Date.now()) { this.cancel(); this.view = { ...this.view, phase: 'expired' }; }
    return structuredClone(this.view);
  }
}

export async function checkCatalog(access, { codexAccount, codex = false, fetcher = fetch } = {}) {
  const endpoint = codex ? 'https://chatgpt.com/backend-api/codex/models?client_version=0.166.0' : `${RESOURCE}/models`;
  const headers = { Authorization: `Bearer ${access}` };
  if (codex && codexAccount && /^[a-zA-Z0-9_-]{1,160}$/.test(codexAccount)) headers['ChatGPT-Account-ID'] = codexAccount;
  try {
    const response = await jsonRequest(endpoint, { headers }, CATALOG_LIMIT, fetcher);
    const entries = response.body.models;
    return { status: response.status, requestId: response.requestId, responseBytes: response.responseBytes,
      bodyShape: Array.isArray(entries) ? 'models-array' : Array.isArray(response.body.data) ? 'api-model-list' : 'other-json',
      modelCount: Array.isArray(entries) ? entries.length : null,
      accepted: Array.isArray(entries), checkedAt: new Date().toISOString() };
  } catch (error) { return { accepted: false, ...safeDiagnostic(error), checkedAt: new Date().toISOString() }; }
}
