// Runs before the bundle so the first paint already uses the saved theme. A separate file, not an
// inline script, because the CSP is default-src 'self'. Storage key must match src/app/useTheme.ts.
(function () {
  var theme = null;
  try {
    var stored = localStorage.getItem('flow-cortex:admin:theme');
    if (stored === 'light' || stored === 'dark') theme = stored;
  } catch (error) {
    // Storage blocked: fall back to the system preference below.
  }
  if (!theme && window.matchMedia) {
    theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  // Same fallback as systemDefault in src/app/useTheme.ts.
  if (!theme) theme = 'dark';
  document.documentElement.setAttribute('data-theme', theme);
  var meta = document.querySelector('meta[name="color-scheme"]');
  if (meta) meta.setAttribute('content', theme);
})();
