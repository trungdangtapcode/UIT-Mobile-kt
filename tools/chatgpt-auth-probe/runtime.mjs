import { mkdir, mkdtemp, lstat, readFile, writeFile, rename, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { random } from './auth.mjs';

export function lifetimeMinutes(value = '0') {
  if (!/^\d+$/.test(value) || Number(value) > 10080) throw new Error('Invalid AUTH_PROBE_TTL_MINUTES');
  return Number(value);
}

/** Persist only the local UI capability, never an OpenAI credential. */
export async function prepareRuntime(directory) {
  const persistent = Boolean(directory);
  const path = persistent ? resolve(directory) : await mkdtemp(join(tmpdir(), 'uit-auth-probe-control-'));
  await mkdir(path, { recursive: true, mode: 0o700 });
  const info = await lstat(path);
  if (!info.isDirectory() || (info.mode & 0o077) || info.uid !== process.getuid()) throw new Error('Runtime directory must be private and owned by the current user');
  const keyPath = join(path, 'control-key');
  let key;
  try {
    const keyInfo = await lstat(keyPath);
    if (!keyInfo.isFile() || (keyInfo.mode & 0o077) || keyInfo.uid !== process.getuid()) throw new Error('Unsafe local control-key file');
    key = (await readFile(keyPath, 'utf8')).trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(key)) throw new Error('Invalid local control key');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    key = random(); await writeFile(keyPath, key + '\n', { flag: 'wx', mode: 0o600 });
  }
  return { path, key, persistent };
}

export async function writeLaunch(runtime, url, port, ttl) {
  const temporary = join(runtime.path, `launch-${random()}.tmp`);
  await writeFile(temporary, JSON.stringify({ url, port, pid: process.pid, autoStopMinutes: ttl }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  await rename(temporary, join(runtime.path, 'launch.json'));
}

export async function cleanRuntime(runtime) {
  if (!runtime.persistent) { await rm(runtime.path, { recursive: true, force: true }); return; }
  const file = join(runtime.path, 'launch.json');
  try { if (JSON.parse(await readFile(file, 'utf8')).pid === process.pid) await rm(file, { force: true }); } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
