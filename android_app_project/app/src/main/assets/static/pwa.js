(() => {
  if (!("serviceWorker" in navigator)) return;
  if (!window.isSecureContext) return;
  const isAndroidApp = /\bMoBaiApp\//.test(navigator.userAgent);

  if (isAndroidApp) {
    navigator.serviceWorker.getRegistrations()
      .then((registrations) => Promise.all(registrations.map((registration) => registration.unregister())))
      .catch(() => {});
    if ("caches" in window) {
      caches.keys()
        .then((keys) => Promise.all(keys.filter((key) => key.startsWith("mbai-gpt-shell-")).map((key) => caches.delete(key))))
        .catch(() => {});
    }
    return;
  }

  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/service-worker.js", { scope: "/" }).catch(() => {
      // PWA support is opportunistic; the WebUI must still work as a normal page.
    });
  });
})();
