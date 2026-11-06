import { spawn } from 'node:child_process';
import { mkdtemp, chmod, readFile, rm, lstat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { ProbeError, inspectDirectGrant, verifyIdentity, safeDiagnostic } from './auth.mjs';

// Public OAuth client ID from openai/codex rust-v0.166.0 login/src/auth/manager.rs.
export const CODEX_CLIENT_ID = 'app_EMoamEEZ73f0CkXaXp7hrann';

/** Runs only auth/account RPCs in a fresh CODEX_HOME. Never reads the owner's Codex profile. */
export class CodexDevice {
  constructor(binary = 'codex', { onAuthenticated = async () => {}, keys } = {}) {
    this.binary = binary; this.onAuthenticated = onAuthenticated; this.keys = keys;
    this.view = { phase: 'idle', tokenReceived: false, identityVerified: false, directPermission: false, scopes: [], credentialSource: 'isolated Codex device-code flow' };
    this.pending = new Map(); this.counter = 0; this.buffer = ''; this.closed = false;
    this.credentials = null; this.subject = null;
  }
  async start() {
    if (this.child || this.closed || this.starting) throw new ProbeError('new_comparison_required');
    this.starting = true;
    this.home = await mkdtemp(join(tmpdir(), 'uit-auth-probe-codex-'));
    await chmod(this.home, 0o700);
    if (this.closed) { await rm(this.home, { recursive: true, force: true }); throw new ProbeError('cancelled'); }
    // An allowlist deliberately excludes OPENAI_API_KEY, existing CODEX_* auth and proxy hooks.
    const env = { PATH: process.env.PATH || '/usr/local/bin:/usr/bin:/bin', HOME: this.home, CODEX_HOME: this.home,
      XDG_CONFIG_HOME: this.home, XDG_CACHE_HOME: this.home, TMPDIR: this.home, NO_COLOR: '1', RUST_LOG: 'off' };
    this.view.phase = 'starting_codex';
    this.child = spawn(this.binary, ['app-server', '--listen', 'stdio://', '-c', 'cli_auth_credentials_store="file"', '-c', 'features.daemon_auto_start=false', '-c', 'analytics.enabled=false', '-c', 'feedback.enabled=false'], {
      cwd: this.home, env, stdio: ['pipe', 'pipe', 'ignore'],
    });
    this.child.on('error', () => this.fail(new ProbeError('codex_unavailable')));
    this.child.stdin.on('error', () => { if (!this.closed) this.fail(new ProbeError('codex_unavailable')); });
    this.child.on('exit', () => { if (!this.closed && !['authenticated', 'failed'].includes(this.view.phase)) this.fail(new ProbeError('codex_exited')); });
    this.child.stdout.on('data', chunk => {
      this.buffer += chunk.toString('utf8');
      if (this.buffer.length > 1048576) { this.fail(new ProbeError('codex_message_too_large')); void this.close(); return; }
      let newline;
      while ((newline = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, newline); this.buffer = this.buffer.slice(newline + 1);
        let message;
        try { message = JSON.parse(line); } catch { continue; }
        if (Object.hasOwn(message, 'id') && this.pending.has(message.id)) {
          const request = this.pending.get(message.id); this.pending.delete(message.id); clearTimeout(request.timer);
          if (message.error) request.reject(new ProbeError('codex_rpc_error', { rpcCode: Number.isInteger(message.error.code) ? message.error.code : null }));
          else request.resolve(message.result);
        } else if (message.method === 'account/login/completed') {
          this.completion = message.params;
          if (this.loginId) void this.finish();
        }
      }
    });
    await this.rpc('initialize', { clientInfo: { name: 'uit_mobile_auth_probe', title: 'UIT Mobile Auth Probe', version: '0.1.0' } });
    this.child.stdin.write(JSON.stringify({ method: 'initialized', params: {} }) + '\n');
    const result = await this.rpc('account/login/start', { type: 'chatgptDeviceCode' });
    if (result?.type !== 'chatgptDeviceCode' || typeof result.loginId !== 'string' || result.loginId.length > 200 || result.verificationUrl !== 'https://auth.openai.com/codex/device' || !/^[A-Z0-9-]{4,32}$/i.test(result.userCode || '')) throw new ProbeError('invalid_device_code_response');
    this.loginId = result.loginId;
    this.view = { ...this.view, phase: 'waiting_for_device_approval', verificationUrl: result.verificationUrl, userCode: result.userCode, startedAt: new Date().toISOString() };
    this.expiry = setTimeout(() => { this.fail(new ProbeError('device_attempt_expired')); void this.close(); }, 15 * 60 * 1000);
    this.expiry.unref();
    if (this.completion) void this.finish();
  }
  rpc(method, params) {
    if (!['initialize', 'account/login/start', 'account/login/cancel'].includes(method)) return Promise.reject(new ProbeError('rpc_not_allowed'));
    return new Promise((resolve, reject) => {
      if (this.closed || !this.child?.stdin?.writable) { reject(new ProbeError('codex_unavailable')); return; }
      const id = ++this.counter;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new ProbeError('codex_request_timeout')); }, 30000);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n', error => {
        if (error) { this.pending.delete(id); clearTimeout(timer); reject(new ProbeError('codex_unavailable')); }
      });
    });
  }
  async finish() {
    if (this.finishing || this.closed || this.completion?.loginId !== this.loginId) return;
    this.finishing = true; clearTimeout(this.expiry);
    try {
      if (this.completion.success !== true) throw new ProbeError('codex_login_failed');
      this.view.phase = 'verifying_identity';
      const path = join(this.home, 'auth.json');
      const info = await lstat(path);
      if (!info.isFile() || info.size > 262144 || (info.mode & 0o077) !== 0 || info.uid !== process.getuid?.()) throw new ProbeError('unsafe_isolated_credential_file');
      const saved = JSON.parse(await readFile(path, 'utf8'));
      const token = saved.tokens;
      if (!token || typeof token.id_token !== 'string' || typeof token.access_token !== 'string' || token.access_token.length < 20 || token.access_token.length > 32768 || !/^[\x21-\x7e]+$/.test(token.access_token)) throw new ProbeError('invalid_codex_credentials');
      const identity = await verifyIdentity(token.id_token, CODEX_CLIENT_ID, undefined, this.keys);
      const grant = await inspectDirectGrant(token.access_token, this.keys);
      if (this.closed) return;
      this.subject = identity.subject;
      this.credentials = { access: token.access_token, account: typeof token.account_id === 'string' ? token.account_id : null };
      this.view = { phase: 'authenticated', tokenReceived: true, identityVerified: true, directPermission: grant.directPermission,
        scopes: grant.scopes, accessClaimsVerified: grant.verified, refreshTokenReceived: Boolean(token.refresh_token),
        credentialSource: 'Codex client, not a new UIT SIWC registration', completedAt: new Date().toISOString() };
      await this.stopProcess();
      await rm(this.home, { recursive: true, force: true });
      await this.onAuthenticated(this);
    } catch (error) { if (!this.closed) this.fail(error); }
  }
  fail(error) {
    delete this.view.userCode; delete this.view.verificationUrl;
    this.view = { ...this.view, phase: 'failed', diagnostic: safeDiagnostic(error) };
    for (const request of this.pending.values()) { clearTimeout(request.timer); request.reject(new ProbeError('codex_unavailable')); }
    this.pending.clear();
  }
  publicView() { return structuredClone(this.view); }
  async stopProcess() {
    if (this.child && this.child.exitCode === null && this.child.signalCode === null) {
      this.child.stdin.end(); this.child.kill('SIGTERM');
      await new Promise(resolve => {
        const timer = setTimeout(() => { this.child.kill('SIGKILL'); resolve(); }, 1500);
        this.child.once('exit', () => { clearTimeout(timer); resolve(); });
      });
    }
  }
  async close() {
    this.closed = true; clearTimeout(this.expiry);
    for (const request of this.pending.values()) { clearTimeout(request.timer); request.reject(new ProbeError('cancelled')); }
    this.pending.clear();
    await this.stopProcess();
    this.credentials = null; this.subject = null;
    if (this.home) await rm(this.home, { recursive: true, force: true });
  }
}
