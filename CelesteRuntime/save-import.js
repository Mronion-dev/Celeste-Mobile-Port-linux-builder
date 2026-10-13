// Desktop saves are data, not game ownership. Accept a save file or Saves ZIP.
window.celesteImportSave = async function(file, { FS, unzip, persist }) {
  if (!file || file.size > 32 * 1024 * 1024) throw new Error('Select a save file or a ZIP of your Saves folder under 32 MiB.');
  const destination = '/libsdl/Celeste/Saves';
  const nameFor = name => {
    const parts = String(name).replaceAll('\\', '/').split('/');
    if (parts.includes('..') || parts.includes('.') || parts[0] === '') throw new Error('Unsafe save path');
    const leaf = parts.at(-1);
    if (!/^[a-z0-9][a-z0-9_. -]{0,179}\.celeste$/i.test(leaf)) return null;
    if (parts.length > 1 && parts.at(-2).toLowerCase() !== 'saves') return null;
    return leaf;
  };
  const data = new Uint8Array(await file.arrayBuffer());
  let files;
  if (/\.celeste$/i.test(file.name)) files = { [file.name]: data };
  else {
    let size = 0;
    files = await new Promise((resolve, reject) => unzip(data, { filter: entry => {
      if (!nameFor(entry.name)) return false;
      size += entry.originalSize;
      if (size > 32 * 1024 * 1024) throw new Error('Expanded save files exceed 32 MiB');
      return true;
    } }, (error, result) => error ? reject(error) : resolve(result)));
  }
  const saves = new Map();
  for (const [path, bytes] of Object.entries(files)) {
    const name = nameFor(path);
    if (!name) continue;
    if (saves.has(name.toLowerCase())) throw new Error('Multiple saves have the same filename; ZIP only one Saves folder.');
    saves.set(name.toLowerCase(), { name, bytes });
  }
  if (!saves.size) throw new Error('No .celeste saves found. Select a save file or ZIP your desktop Saves folder.');
  FS.mkdirTree(destination);
  const exists = path => { try { FS.stat(path); return true; } catch { return false; } };
  for (const { name, bytes } of saves.values()) {
    const path = `${destination}/${name}`, backup = path + '.before-import';
    if (exists(path) && !exists(backup)) FS.writeFile(backup, FS.readFile(path));
    FS.writeFile(path, bytes);
  }
  await persist(destination);
  return saves.size;
};
