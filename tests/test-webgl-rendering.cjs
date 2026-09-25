const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../CelesteRuntime/_framework/dotnet.native.j5v2s4hecp.js'), 'utf8');
const between = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const calls = [], messages = [];
const gl = {
  texImage2D: (...args) => calls.push(args),
  texStorage2D: (...args) => calls.push(args),
};
const context = vm.createContext({ GLctx: gl, out: () => {}, console,
  ENVIRONMENT_IS_PTHREAD: true, postMessage: m => messages.push(m),
  performance: { now: () => 1000 }, Uint8Array,
  emscriptenWebGLGetTexPixelData: (...args) => args,
});
vm.runInContext(between('function _glTexImage2D(', 'var _emscripten_glTexImage2D'), context);
vm.runInContext(between('var _glTexStorage2D =', 'var _emscripten_glTexStorage2D'), context);
context._glTexImage2D(3553, 0, 0x8c41, 320, 180, 0, 0x1907, 0x1401, 0);
assert.deepEqual(calls.pop(), [3553, 0, 0x8c43, 320, 180, 0, 0x1908, 0x1401, null]);
// Uploaded RGB data must retain its original three-component layout.
context._glTexImage2D(3553, 0, 0x8c41, 3, 2, 0, 0x1907, 0x1401, 64);
assert.equal(calls.at(-1)[2], 0x8c41);
assert.equal(calls.pop()[6], 0x1907);
gl.currentPixelUnpackBufferBinding = 1;
context._glTexImage2D(3553, 0, 0x8c41, 3, 2, 0, 0x1907, 0x1401, 0);
assert.equal(calls.pop()[2], 0x8c41);
gl.currentPixelUnpackBufferBinding = 0;
context._glTexStorage2D(3553, 1, 0x8c41, 320, 180);
assert.equal(calls.pop()[2], 0x8c43);
context._glTexImage2D(3553, 0, 0x881b, 320, 180, 0, 0x1907, 0x140b, 0);
assert.equal(calls.pop()[2], 0x881b); // Actual RGB16F is unrelated.

const constants = ['DRAW_FRAMEBUFFER_BINDING', 'READ_FRAMEBUFFER_BINDING',
  'PIXEL_PACK_BUFFER_BINDING', 'PACK_ALIGNMENT', 'PACK_ROW_LENGTH',
  'PACK_SKIP_PIXELS', 'PACK_SKIP_ROWS', 'RGBA', 'UNSIGNED_BYTE'];
for (const name of constants) gl[name] = name;
gl.READ_FRAMEBUFFER = gl.READ_FRAMEBUFFER_BINDING;
gl.PIXEL_PACK_BUFFER = gl.PIXEL_PACK_BUFFER_BINDING;
const state = new Map(constants.map((name, index) => [name, index + 1]));
state.set(gl.DRAW_FRAMEBUFFER_BINDING, null);
const initial = new Map(state);
gl.getParameter = name => state.get(name);
gl.bindFramebuffer = gl.bindBuffer = gl.pixelStorei = (name, value) => state.set(name, value);
gl.isContextLost = () => false;
gl.drawingBufferWidth = 320;
gl.drawingBufferHeight = 180;
let reads = 0, color = [0, 0, 0, 255];
gl.readPixels = (...args) => { reads++; args.at(-1).set(color); };
vm.runInContext(between('var celesteCheckDisplayFrame =', '/** @suppress {duplicate } */ var _glBlitFramebuffer'), context);
context.celesteCheckDisplayFrame();
assert.equal(messages.length, 0, 'Black frames must not dismiss startup');
assert.equal(reads, 9);
assert.deepEqual(state, initial);
gl.__celesteNextFrameCheck = 0;
color = [30, 50, 70, 255];
context.celesteCheckDisplayFrame();
assert.equal(messages.length, 1);
assert.equal(messages[0].cmd, 'celesteFrameConfirmed');
assert.match(messages[0].text, /320x180 pixel=30,50,70,255/);
assert.deepEqual(state, initial, 'Readback must restore all framebuffer and pack state');
context.celesteCheckDisplayFrame();
assert.equal(messages.length, 1, 'Only notify once');
gl.__celesteFrameConfirmed = false;
gl.__celesteNextFrameCheck = 0;
gl.readPixels = () => { throw new Error('context read failed'); };
context.celesteCheckDisplayFrame();
assert.deepEqual(state, initial, 'Failed readback must also restore state');
assert.equal(messages.length, 1);

const html = fs.readFileSync(path.join(__dirname, '../CelesteRuntime/index.html'), 'utf8');
const scripts = [];
for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  // Inline modules contain no imports; Script checks their syntax as well.
  new vm.Script(match[1]);
  scripts.push(match[1]);
}
let revealed = 0, status;
const page = { celesteForceGameVisible: () => revealed++, celesteSetBootStatus: text => status = text };
const pageContext = vm.createContext({ window: page, console: { log() {} },
  fetch: () => Promise.resolve(), setTimeout: callback => callback(),
});
vm.runInContext(scripts.find(script => script.includes('const levels =')), pageContext);
vm.runInContext('console.log("GAME DISPLAYED")', pageContext);
assert.equal(revealed, 0);
assert.match(status, /first rendered frame/);
vm.runInContext('console.info("[android-port] frame confirmed 320x180 pixel=30,50,70,255")', pageContext);
assert.equal(revealed, 1);
assert.equal(page.__celesteAndroidRendered, true);
console.log('WebGL allocation, frame confirmation, state restoration and inline syntax checks passed.');
