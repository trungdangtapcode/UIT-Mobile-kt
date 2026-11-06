import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { ProbeError } from './auth.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const defaultClasspath = fileURLToPath(new URL('../../core/data/build/outputs/chatgpt-native/classpath.txt', import.meta.url));
const scalarFields = ['result', 'model', 'connectionMode', 'runtime', 'registration', 'requests', 'networkRequests', 'contentType', 'protocol', 'actualSubscriberInference', 'inferenceAttempted', 'credentialWrittenToDisk', 'universityAccountUsed'];
const diagnosticFields = ['operation', 'status', 'code', 'requestId', 'bodyShape', 'phase', 'parameter', 'contentType', 'contentEncoding', 'responseBytes'];

export class NativeCheck {
  constructor({ java = process.env.AUTH_PROBE_JAVA || 'java', classpath = process.env.AUTH_PROBE_NATIVE_CLASSPATH || defaultClasspath, launch = spawn } = {}) {
    this.java = java; this.classpath = classpath; this.launch = launch;
    this.view = { phase: 'idle', started: false };
  }
  publicView({ report = false } = {}) {
    const view = structuredClone(this.view);
    if (report && view.result) delete view.result.privateServiceHint;
    return view;
  }
  async start(flow) {
    if (this.child) throw new ProbeError('native_check_in_progress');
    const expiresAt = Math.floor(Date.parse(flow.view.completedAt || '') / 1000) + (flow.view.expiresIn || 0);
    if (!flow.credentials?.access || !flow.view.identityVerified || !flow.view.directPermission || !Number.isFinite(expiresAt) || expiresAt < Date.now() / 1000 + 120) throw new ProbeError('fresh_browser_sign_in_required');
    let classpath;
    try { classpath = (await readFile(this.classpath, 'utf8')).trim(); } catch { throw new ProbeError('native_runner_not_prepared'); }
    if (!classpath || classpath.length > 131072 || /[\r\n]/.test(classpath)) throw new ProbeError('invalid_native_classpath');
    const env = {};
    for (const key of ['PATH', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'JAVA_HOME', 'ANDROID_HOME', 'SSL_CERT_FILE', 'SSL_CERT_DIR']) if (process.env[key]) env[key] = process.env[key];
    const grant = { access: flow.credentials.access, subject: flow.subject, clientId: flow.clientId, scope: flow.view.scopes.join(' '), identityVerified: true, expiresAt };
    const markers = [grant.access, grant.subject, grant.clientId].filter(x => typeof x === 'string' && x.length > 10);
    this.view = { phase: 'running', started: true, startedAt: new Date().toISOString() };
    const child = this.launch(this.java, ['-cp', classpath, 'vn.edu.uit.mobile.data.ChatGptSubscriberProbe'], { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child = child;
    let output = '', outputBytes = 0, report;
    const failed = code => { if (this.child === child) this.view = { ...this.view, phase: 'failed', diagnostic: { code } }; };
    const timer = setTimeout(() => { failed('native_check_timeout'); child.kill('SIGKILL'); }, 210000); timer.unref();
    child.stdout.on('data', chunk => {
      if (this.child !== child) return;
      outputBytes += chunk.length;
      output += chunk.toString('utf8');
      if (outputBytes > 65536) { failed('native_output_too_large'); child.kill('SIGKILL'); return; }
      const lines = output.split('\n'); output = lines.pop();
      for (const line of lines) {
        if (!line.startsWith('UIT_NATIVE_REPORT ')) continue;
        if (markers.some(marker => line.includes(marker))) { failed('unsafe_native_output_rejected'); child.kill('SIGKILL'); return; }
        try {
          const parsed = JSON.parse(line.slice('UIT_NATIVE_REPORT '.length));
          report = Object.fromEntries(scalarFields.filter(key => ['string', 'number', 'boolean'].includes(typeof parsed[key])).map(key => [key, typeof parsed[key] === 'string' ? parsed[key].slice(0, 300) : parsed[key]]));
          if (parsed.diagnostic && typeof parsed.diagnostic === 'object') report.diagnostic = Object.fromEntries(diagnosticFields.filter(key => ['string', 'number'].includes(typeof parsed.diagnostic[key])).map(key => [key, typeof parsed.diagnostic[key] === 'string' ? parsed.diagnostic[key].slice(0, 300) : parsed.diagnostic[key]]));
          if (typeof parsed.privateServiceHint === 'string') report.privateServiceHint = parsed.privateServiceHint.slice(0, 400);
        } catch { failed('invalid_native_report'); }
      }
    });
    // Never retain or print child stderr: provider traffic and stack traces do not belong in reports.
    child.stderr.resume(); child.stdin.on('error', () => {});
    child.on('error', () => { clearTimeout(timer); failed('native_runner_failed'); if (this.child === child) this.child = null; });
    child.on('close', () => {
      clearTimeout(timer);
      if (this.child !== child) return;
      if (report) this.view = { ...this.view, phase: report.result === 'completed' ? 'completed' : 'failed', completedAt: new Date().toISOString(), result: report };
      else if (this.view.phase === 'running') failed('native_runner_no_report');
      this.child = null;
    });
    child.stdin.end(JSON.stringify(grant));
  }
  async close() {
    const child = this.child; this.child = null;
    if (child) child.kill('SIGKILL');
    this.view = { phase: 'idle', started: false };
  }
}
