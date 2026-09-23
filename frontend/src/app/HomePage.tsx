import { useMeQuery } from '../features/auth/useAuth';

export function HomePage() {
  const meQuery = useMeQuery();

  if (meQuery.isLoading) {
    return <output>Loading…</output>;
  }
  if (meQuery.isError || !meQuery.data) {
    return <p role="alert">Could not load your account.</p>;
  }

  return (
    <section>
      <h1>Welcome, {meQuery.data.name}</h1>
      <p>Signed in as {meQuery.data.email} ({meQuery.data.role}).</p>
    </section>
  );
}
