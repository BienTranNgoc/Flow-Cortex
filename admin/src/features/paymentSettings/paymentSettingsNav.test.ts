import { reachableItems } from '../../app/nav';
import { ADMIN_ME, staffMe } from '../../test/fakeApi';

describe('payment settings navigation entry', () => {
  it('is reachable only with the view key', () => {
    const viewer = staffMe('OPERATION_MANAGER', ['payment.setting.view']);
    expect(reachableItems(viewer).map((item) => item.path)).toContain('/payment-settings');
    expect(reachableItems(ADMIN_ME).map((item) => item.path)).toContain('/payment-settings');
    const noAccess = staffMe('CUSTOMER_SUPPORT', ['support.ticket.view']);
    expect(reachableItems(noAccess).map((item) => item.path)).not.toContain('/payment-settings');
    expect(
      reachableItems({ ...viewer, adminDisabled: true }).map((item) => item.path),
    ).not.toContain('/payment-settings');
  });
});
