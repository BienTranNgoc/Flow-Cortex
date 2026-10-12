import './AppShell.css';

/**
 * Public-route shell (login / register). Lays the page on top of a fixed
 * aurora background so the auth form sits in front of a soft brand glow.
 * Authenticated routes keep using the in-app <Layout /> with their nav;
 * this shell is only for the sign-in / sign-up flow so we never flash
 * auth chrome on a public page.
 */
export function AppShell({ children }: { readonly children: React.ReactNode }) {
  return (
    <div className="public-shell">
      <div className="aurora-bg" aria-hidden="true">
        <div className="aurora-blob aurora-blob--teal" />
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <main className="public-main">{children}</main>
    </div>
  );
}
