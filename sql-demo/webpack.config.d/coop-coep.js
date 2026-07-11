// createPooledSqliteWasmDatabase's opfs-wl VFS needs cross-origin isolation (Atomics.waitAsync
// coordination) — without these headers it fails silently with
// "sqlite3.oo1.OpfsWlDb is not a constructor", no other error. Needed for both
// wasmJsBrowserDevelopmentRun (webpack-dev-server) and any static hosting of the production build
// (GitHub Pages can't set these — see docs/web-targets.md in korm).
config.devServer = config.devServer || {};
config.devServer.headers = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
};
