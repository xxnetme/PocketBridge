/* Offline incremental SHA-256. Read only one slice at a time. */
'use strict';
importScripts('/sha256.min.js');

self.onmessage = async ({ data: file }) => {
  try {
    const hash = self.sha256.create();
    const step = 4 * 1024 * 1024;
    for (let offset = 0; offset < file.size;) {
      const end = Math.min(file.size, offset + step);
      hash.update(new Uint8Array(await file.slice(offset, end).arrayBuffer()));
      offset = end;
      self.postMessage({ bytes: end });
    }
    self.postMessage({ digest: hash.hex() });
  } catch (_) {
    self.postMessage({ error: true });
  }
};
