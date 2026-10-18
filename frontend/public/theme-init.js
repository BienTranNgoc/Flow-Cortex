// Loaded before the app bundle so the first paint already uses the saved or system theme.
// The storage key and the light fallback must match src/app/useTheme.ts.
(function () {
  var theme = null;
  try {
    var stored = localStorage.getItem('flow-cortex:theme');
    if (stored === 'light' || stored === 'dark') theme = stored;
  } catch (e) {
    // Storage is unavailable in some private modes; the system preference decides instead.
  }
  if (!theme) {
    var prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    theme = prefersDark ? 'dark' : 'light';
  }
  document.documentElement.dataset.theme = theme;
  var meta = document.querySelector('meta[name="color-scheme"]');
  if (meta) meta.setAttribute('content', theme);
})();
