// Injected only by the isolated test server into the existing pthread worker.
function runAudioProbe(options) {
  const log = text => Module.out('[audio-probe] ' + text);
  const call = (name, ...args) => {
    log('ENTER ' + name + ' ' + JSON.stringify(args));
    const result = Module.wasmExports['probe_' + name](...args);
    log('LEAVE ' + name + ' result=' + result);
    if(result !== 0) throw Error(name + ' failed: ' + result);
  };
  const pointer = Module._malloc(4);
  try {
    call('FMOD_Studio_System_Create', pointer, 131847);
    const studio = Module.getValue(pointer, 'i32');
    call('FMOD_Studio_System_GetCoreSystem', studio, pointer);
    const core = Module.getValue(pointer, 'i32');
    call('FMOD_System_GetVersion', core, pointer);
    log('version=' + Module.getValue(pointer, 'i32').toString(16));
    call('FMOD_Studio_System_Initialize', studio, 1024, (options.sync ? 4 : 0) | (options.loadFromUpdate ? 16 : 0), options.sync ? 3 : 0, 0);
    call('FMOD_Studio_System_Update', studio);
    call('FMOD_Studio_System_Release', studio);
    log('PASS');
  } catch(error) { log('FAIL ' + error.stack); }
  finally { Module._free(pointer); postMessage({cmd:'callHandler', handler:'print', args:['[audio-probe] DONE']}); }
}
