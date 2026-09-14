export function App() {
  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem', maxWidth: 640 }}>
      <h1>Flow-Cortex</h1>
      <p>
        Frontend skeleton is running. Backend health check:{' '}
        <code>GET {import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'}/health</code>
      </p>
    </main>
  );
}
