import i18n from '../../lib/i18n';
import { roleLabel, statusLabel } from './enumLabels';

describe('enumLabels', () => {
  const t = i18n.t.bind(i18n);

  it('reads a missing account status as active', () => {
    expect(statusLabel(t, undefined)).toBe('Active');
  });

  it('shows a status code the console does not know as sent', () => {
    expect(statusLabel(t, 'PENDING')).toBe('PENDING');
  });

  it('shows no role label when the account has no role', () => {
    expect(roleLabel(t, undefined)).toBe('');
  });
});
