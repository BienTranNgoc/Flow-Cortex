import { useQuery } from '@tanstack/react-query';
import { Download, Plus, RefreshCw } from 'lucide-react';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import {
  fetchCreditSummary,
  fetchCreditTransactions,
  fetchCreditWallets,
  type AdminCreditWallet,
  type CreditTransaction,
} from '../../app/mockAdminApi';

export function CreditsPage() {
  usePageTitle('Credits & Wallets');
  const summary = useQuery({ queryKey: ['admin', 'credits', 'summary'], queryFn: fetchCreditSummary });
  const wallets = useQuery({ queryKey: ['admin', 'credits', 'wallets'], queryFn: fetchCreditWallets });
  const txns = useQuery({ queryKey: ['admin', 'credits', 'transactions'], queryFn: fetchCreditTransactions });

  return (
    <div>
      <PageHeading
        eyebrow="Credits & Billing"
        title="Credits & Wallets"
        description="Track issued credits, daily consumption, refunds, and every wallet adjustment."
        actions={
          <>
            <button type="button" className="cortex-admin-btn cortex-admin-btn--ghost">
              <Download size={16} aria-hidden="true" />
              <span>Export CSV</span>
            </button>
            <button type="button" className="cortex-admin-btn cortex-admin-btn--primary">
              <Plus size={16} aria-hidden="true" />
              <span>Manual grant</span>
            </button>
          </>
        }
      />

      <section className="cortex-admin-metric-grid" aria-label="Credit statistics">
        <MetricCard
          label="Credits granted"
          value={summary.data ? summary.data.totalGranted.toLocaleString() : '—'}
          hint="Across every account"
        />
        <MetricCard
          label="Consumed today"
          value={summary.data ? summary.data.totalConsumedToday.toLocaleString() : '—'}
          hint="Step charges only"
        />
        <MetricCard
          label="Refunded"
          value={summary.data ? summary.data.totalRefunded.toLocaleString() : '—'}
          hint="Auto refunds for server errors"
        />
        <MetricCard
          label="Active wallets"
          value={summary.data ? summary.data.activeWallets.toLocaleString() : '—'}
          hint="Non-zero balance"
        />
      </section>

      <section className="cortex-admin-panel" aria-label="User wallets">
        <header className="cortex-admin-section-heading">
          <div>
            <h2>User wallets</h2>
            <p>Adjust any row to manually grant or refund credits.</p>
          </div>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--ghost"
            onClick={() => wallets.refetch()}
            disabled={wallets.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            <span>{wallets.isFetching ? 'Refreshing…' : 'Refresh'}</span>
          </button>
        </header>
        {wallets.isLoading && <output className="cortex-admin-loading">Loading wallets…</output>}
        {wallets.isError && (
          <div className="cortex-admin-error">
            <p role="alert">Could not load wallets.</p>
            <button type="button" className="cortex-admin-btn" onClick={() => wallets.refetch()}>
              Retry
            </button>
          </div>
        )}
        {wallets.data && wallets.data.length > 0 && (
          <div className="cortex-admin-table-wrapper">
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">User</th>
                  <th scope="col">Current balance</th>
                  <th scope="col">Total consumed</th>
                  <th scope="col">Total granted</th>
                  <th scope="col">Last grant</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {wallets.data.map((w) => (
                  <WalletRow key={w.userId} wallet={w} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="cortex-admin-panel" aria-label="Transactions" style={{ marginTop: '1rem' }}>
        <header className="cortex-admin-section-heading">
          <div>
            <h2>Transactions &amp; refunds</h2>
            <p>Every charge, auto-refund, and manual adjustment.</p>
          </div>
        </header>
        {txns.isLoading && <output className="cortex-admin-loading">Loading transactions…</output>}
        {txns.isError && (
          <div className="cortex-admin-error">
            <p role="alert">Could not load transactions.</p>
            <button type="button" className="cortex-admin-btn" onClick={() => txns.refetch()}>
              Retry
            </button>
          </div>
        )}
        {txns.data && txns.data.length > 0 && (
          <div className="cortex-admin-table-wrapper">
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">ID</th>
                  <th scope="col">Type</th>
                  <th scope="col">User</th>
                  <th scope="col">Run / step</th>
                  <th scope="col">Amount</th>
                  <th scope="col">When</th>
                </tr>
              </thead>
              <tbody>
                {txns.data.map((t) => (
                  <TransactionRow key={t.id} txn={t} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function WalletRow({ wallet }: Readonly<{ wallet: AdminCreditWallet }>) {
  return (
    <tr>
      <td data-label="User">
        <code className="cortex-admin-table-job-id">{wallet.email}</code>
      </td>
      <td data-label="Current balance">
        <strong>{wallet.currentBalance.toLocaleString()}</strong>
      </td>
      <td data-label="Total consumed">{wallet.totalConsumed.toLocaleString()}</td>
      <td data-label="Total granted">{wallet.totalGranted.toLocaleString()}</td>
      <td data-label="Last grant">
        <Timestamp value={wallet.lastGranted} />
      </td>
      <td data-label="Actions">
        <div className="cortex-admin-stack">
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
            Adjust
          </button>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
            Refund
          </button>
        </div>
      </td>
    </tr>
  );
}

function TransactionRow({ txn }: Readonly<{ txn: CreditTransaction }>) {
  const tone =
    txn.type === 'SYSTEM_REFUND' || txn.amount > 0
      ? 'success'
      : txn.type === 'MANUAL_GRANT'
        ? 'info'
        : 'muted';
  return (
    <tr>
      <td data-label="ID">
        <code className="cortex-admin-table-job-id">{txn.id}</code>
      </td>
      <td data-label="Type">
        <StatusPill tone={tone} label={txn.type} />
      </td>
      <td data-label="User">{txn.email}</td>
      <td data-label="Run / step">
        {txn.runId ? <code className="cortex-admin-table-job-id">{txn.runId}</code> : '—'}
        {txn.stepName && <p className="cortex-admin-stale">{txn.stepName}</p>}
      </td>
      <td data-label="Amount">
        <strong style={{ color: txn.amount > 0 ? 'var(--admin-green)' : 'var(--admin-ink)' }}>
          {txn.amount > 0 ? '+' : ''}
          {txn.amount.toLocaleString()}
        </strong>
      </td>
      <td data-label="When">
        <Timestamp value={txn.createdAt} />
      </td>
    </tr>
  );
}
