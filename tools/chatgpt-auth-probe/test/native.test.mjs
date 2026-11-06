import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { NativeCheck } from '../native-check.mjs';

function flow() { return { credentials: { access: 'fixture-native-access-token-not-real' }, subject: 'fixture-native-subject', clientId: 'fixture-native-client', view: { identityVerified: true, directPermission: true, scopes: ['resource.invoke', 'chatgpt.tokens.use.direct'], completedAt: new Date().toISOString(), expiresIn: 3600 } }; }
async function finished(check) { for (let i = 0; i < 100 && check.child; i++) await new Promise(resolve => setTimeout(resolve, 10)); assert.equal(check.child, null); }

test('native diagnostic requires a fresh verified browser grant before spawning', async () => {
  let launched = false;
  const check = new NativeCheck({ launch() { launched = true; } });
  const expired = flow(); expired.view.completedAt = '2020-01-01T00:00:00.000Z';
  await assert.rejects(check.start(expired), error => error.code === 'fresh_browser_sign_in_required');
  const unverified = flow(); unverified.view.identityVerified = false;
  await assert.rejects(check.start(unverified), error => error.code === 'fresh_browser_sign_in_required');
  assert.equal(launched, false);
});

test('native grant goes through stdin only; safe report excludes the private service hint', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'uit-native-test-'));
  const file = join(directory, 'classpath.txt'); await writeFile(file, 'fixture-classpath');
  let inspected;
  const check = new NativeCheck({ classpath: file, launch(java, args, options) {
    inspected = { java, args, options };
    return spawn(process.execPath, ['-e', `let input='';process.stdin.on('data',x=>input+=x);process.stdin.on('end',()=>{const g=JSON.parse(input);if(!g.access||process.env.OPENAI_API_KEY||process.env.CPASSWORD)process.exit(7);const r={result:'completed',requests:2,inferenceAttempted:true,actualSubscriberInference:true,privateServiceHint:'Private redacted service detail'};const out='UIT_NATIVE_REPORT '+JSON.stringify(r)+'\\n';process.stdout.write(out.slice(0,30));setTimeout(()=>process.stdout.write(out.slice(30)),5);});`], options);
  } });
  try {
    await check.start(flow()); await finished(check);
    assert.equal(check.view.phase, 'completed');
    assert.equal(check.view.result.actualSubscriberInference, true);
    assert.equal(check.publicView().result.privateServiceHint, 'Private redacted service detail');
    assert.equal(check.publicView({ report: true }).result.privateServiceHint, undefined);
    assert.equal(JSON.stringify(inspected).includes('fixture-native-access-token'), false);
    assert.equal(inspected.options.env.OPENAI_API_KEY, undefined);
    assert.equal(inspected.options.env.CPASSWORD, undefined);
  } finally { await check.close(); await rm(directory, { recursive: true, force: true }); }
});

test('native output containing an actual grant marker is rejected rather than displayed', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'uit-native-test-'));
  const file = join(directory, 'classpath.txt'); await writeFile(file, 'fixture-classpath');
  const check = new NativeCheck({ classpath: file, launch(java, args, options) {
    return spawn(process.execPath, ['-e', `let input='';process.stdin.on('data',x=>input+=x);process.stdin.on('end',()=>{console.log('UIT_NATIVE_REPORT '+JSON.stringify({result:'completed',privateServiceHint:JSON.parse(input).access}));});`], options);
  } });
  try {
    await check.start(flow()); await finished(check);
    assert.equal(check.view.phase, 'failed');
    assert.equal(check.view.diagnostic.code, 'unsafe_native_output_rejected');
    assert.equal(JSON.stringify(check.publicView()).includes('fixture-native-access-token'), false);
  } finally { await check.close(); await rm(directory, { recursive: true, force: true }); }
});
