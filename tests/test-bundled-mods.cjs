const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'CelesteRuntime/bundle.js'), 'utf8');
const files = new Map();
const names = ['MobileBridge', 'MobileTweaks', 'MouseUI'];
for (const name of names) files.set(`/libsdl/Celeste/Mods/${name}.zip`, Buffer.from('stale'));
files.set('/libsdl/Celeste/Mods/MobileBridge', Buffer.alloc(0));
const context = vm.createContext({
  console, Uint8Array, Uint16Array, Int32Array, TextDecoder, TextEncoder, queueMicrotask,
  window: {}, mkdirp() {}, probeWritable() {}, persistRuntimeDir: async () => {},
  dotnet: { instance: { Module: { FS: {
    writeFile: (name, data) => files.set(name, Buffer.from(data)),
    analyzePath: name => ({ exists: files.has(name) }),
    unlink: name => files.delete(name),
    stat: () => ({ mode: 0x8000 | 0o644 }),
  } } } },
  fetch: async name => ({ ok: true, arrayBuffer: async () => fs.readFileSync(path.join(root, 'CelesteRuntime', name)) }),
});
vm.runInContext(source.slice(source.indexOf('// node_modules/.pnpm/fflate@'), source.indexOf('// game.js')), context);
vm.runInContext(source.slice(source.indexOf('async function installBundledMods()'), source.indexOf('window.celesteInstallBundledMods =')), context);
(async () => {
  await context.installBundledMods();
  for (const name of names) {
    assert.ok(files.get(`/libsdl/Celeste/Mods/${name}/${name}.dll`)?.length > 0);
    assert.ok(files.get(`/libsdl/Celeste/Mods/${name}/everest.yaml`)?.length > 0);
    assert.equal(files.has(`/libsdl/Celeste/Mods/${name}.zip`), false);
  }
  assert.match(files.get('/libsdl/Celeste/Mods/MobileTweaks/Dialog/English.txt').toString(), /turn off your phone/);
  assert.match(files.get('/libsdl/Celeste/Mods/MobileBridge/Dialog/English.txt').toString(), /MOBILEBRIDGE_MOD_BROWSER=MOD MANAGER/);
  assert.ok([...files.keys()].every(name => !name.includes('/bin/')));
  assert.equal(files.has('/libsdl/Celeste/Mods/MobileBridge'), false, 'legacy folder-as-file repaired');
  context.unzip = (_, cb) => cb(null, { 'everest.yaml': new Uint8Array(1), 'MobileBridge.dll': new Uint8Array(1), '../escape': new Uint8Array(1) });
  await assert.rejects(context.installBundledMods(), /Unsafe bundled mod path/);
  let saved;
  const runtimeFS = context.dotnet.instance.Module.FS;
  runtimeFS.analyzePath = () => ({ exists: true });
  runtimeFS.readdir = () => ['.', '..', 'MobileBridge', 'options.celeste'];
  runtimeFS.stat = name => ({ mode: name.endsWith('MobileBridge') ? 0x41ed : 0x81a4 });
  runtimeFS.readFile = name => { assert.ok(!name.endsWith('MobileBridge'), 'must not read a folder as a file'); return Buffer.from('settings'); };
  context.persistenceStore = async (_, callback) => callback({ put: value => { saved = value; } });
  vm.runInContext(source.slice(source.indexOf('async function persistRuntimeDir(path)'), source.indexOf('async function persistRuntimeDirs()')), context);
  await context.persistRuntimeDir('/libsdl/Celeste/Mods');
  assert.deepEqual(Object.keys(saved), ['options.celeste']);
  console.log('Bundled mod extraction and unsafe-path rejection passed without workers.');
})().catch(error => { console.error(error); process.exitCode = 1; });
