const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const runtime = path.join(root, 'CelesteRuntime');
const output = path.join(root, 'tools', 'audio-probe');
const probeWasm = require('./audio-probe-native.cjs')(runtime);
fs.mkdirSync(output, {recursive: true});
const log = fs.createWriteStream(path.join(output, 'results.log'), {flags: 'a'});
const server = http.createServer(async (req, res) => {
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/report' && req.method === 'POST') {
    let body = '';
    for await (const chunk of req) body += chunk;
    const data = JSON.parse(body);
    const line = `${new Date().toISOString()} ${data.scenario} ${data.text}\n`;
    log.write(line);
    if (/audio-probe|ERROR|native thread request|deadlock/.test(data.text)) process.stdout.write(line);
    res.end('ok');
    return;
  }
  let file;
  if (url.pathname.endsWith('dotnet.native.j5v2s4hecp.js')) {
    let source = fs.readFileSync(path.join(runtime, '_framework/dotnet.native.j5v2s4hecp.js'), 'utf8');
    source = source.replace('function ___pthread_create_js(pthread_ptr, attr, startRoutine, arg) {',
      'function ___pthread_create_js(pthread_ptr, attr, startRoutine, arg) { if (ENVIRONMENT_IS_PTHREAD) out("[audio-probe] native thread request " + new Error().stack);');
    res.setHeader('Content-Type', 'text/javascript');
    res.end(source); return;
  }
  if (url.pathname.endsWith('.wasm')) {
    res.setHeader('Content-Type', 'application/wasm');
    res.setHeader('Cache-Control', 'no-store');
    res.end(probeWasm); return;
  }
  if (url.pathname.endsWith('dotnet.native.worker.mjs')) {
    let source = fs.readFileSync(path.join(runtime, '_framework/dotnet.native.worker.mjs'), 'utf8');
    source = source.replace("} else if (e.data.cmd === 'cancel')", "} else if (e.data.cmd === 'audio-probe') { runAudioProbe(e.data.options); } else if (e.data.cmd === 'cancel')");
    res.setHeader('Content-Type', 'text/javascript');
    res.end(source + '\n' + fs.readFileSync(path.join(__dirname, 'audio-probe-worker.js'), 'utf8')); return;
  }
  if (url.pathname === '/' || url.pathname === '/audio-probe.html') file = path.join(__dirname, 'audio-probe.html');
  else file = path.resolve(runtime, '.' + decodeURIComponent(url.pathname));
  if (!file.startsWith(runtime + path.sep) && file !== path.join(__dirname, 'audio-probe.html') && file !== path.join(output, 'CelesteLoader.dr8i5i78r8.dll')) {
    res.writeHead(403).end(); return;
  }
  // This probe must never accidentally load the game or its data pack.
  if (/\/celeste\/|\/data\/|\/bundle\.js$/.test(url.pathname)) {
    res.writeHead(403).end('Game data is excluded from this probe'); return;
  }
  const chunks = [];
  if (file.endsWith('.wasm')) {
    for (let i = 0; fs.existsSync(file + i); i++) chunks.push(file + i);
  } else if (fs.existsSync(file)) chunks.push(file);
  if (!chunks.length) { res.writeHead(404).end(); return; }
  const ext = path.extname(file);
  res.setHeader('Content-Type', ({'.wasm': 'application/wasm', '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html'})[ext] || 'application/octet-stream');
  res.setHeader('Content-Length', chunks.reduce((total, p) => total + fs.statSync(p).size, 0));
  res.setHeader('Cache-Control', ext === '.wasm' ? 'max-age=3600' : 'no-store');
  try {
    for (const chunk of chunks) {
      for await (const buffer of fs.createReadStream(chunk)) {
        if (res.destroyed) return;
        if (!res.write(buffer)) await new Promise(resolve => res.once('drain', resolve));
      }
    }
    res.end();
  } catch (error) { res.destroy(error); }
});
server.listen(8765, '127.0.0.1', () => console.log('Audio probe: http://127.0.0.1:8765/'));
