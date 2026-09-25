const fs = require('node:fs');
const path = require('node:path');
const leb = n => { const a = []; do { const b = n & 127; n >>>= 7; a.push(b | (n ? 128 : 0)); } while (n); return Buffer.from(a); };
const str = s => { const b = Buffer.from(s); return Buffer.concat([leb(b.length), b]); };
function reader(b) {
  return { p: 0, uint() { let n=0,s=0,v; do { v=b[this.p++]; n|=(v&127)<<s; s+=7; } while(v&128); return n>>>0; }, string() { const n=this.uint(), p=this.p; this.p+=n; return b.toString('utf8',p,p+n); } };
}
module.exports = function(runtime) {
  const base = path.join(runtime, '_framework/dotnet.native.w4yfy81t7c.wasm');
  const chunks=[]; for(let i=0;fs.existsSync(base+i);i++) chunks.push(fs.readFileSync(base+i));
  const wasm=Buffer.concat(chunks), sections=[];
  const r=reader(wasm); r.p=8;
  while(r.p<wasm.length) { const id=wasm[r.p++], n=r.uint(), p=r.p; r.p+=n; sections.push({id,data:wasm.subarray(p,p+n)}); }
  const names=new Map();
  for(const section of sections.filter(s=>s.id===0)) {
    const r=reader(section.data); if(r.string()!=='name') continue;
    while(r.p<section.data.length) { const id=section.data[r.p++], n=r.uint(), end=r.p+n;
      if(id===1) { const count=r.uint(); for(let i=0;i<count;i++) { const index=r.uint(), name=r.string(); names.set(name,index); } }
      r.p=end;
    }
  }
  const functions=['FMOD_Studio_System_Create','FMOD_Studio_System_GetCoreSystem','FMOD_Studio_System_Initialize','FMOD_Studio_System_Update','FMOD_Studio_System_Release','FMOD_System_GetVersion'];
  const section=sections.find(s=>s.id===7), er=reader(section.data), count=er.uint();
  section.data=Buffer.concat([leb(count+functions.length),section.data.subarray(er.p),...functions.map(name=>{
    if(!names.has(name)) throw Error('Missing native function '+name);
    return Buffer.concat([str('probe_'+name),Buffer.from([0]),leb(names.get(name))]);
  })]);
  return Buffer.concat([wasm.subarray(0,8),...sections.flatMap(s=>[Buffer.from([s.id]),leb(s.data.length),s.data])]);
};
