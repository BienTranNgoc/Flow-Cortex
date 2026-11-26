import { NAV, reachableItems } from '../../app/nav';
import { ADMIN_ME, staffMe } from '../../test/fakeApi';

describe('reported workflows navigation entry', () => {
  it('sits in the workflow operations group', () => {
    const group = NAV.find((candidate) => candidate.id === 'workflow-ops');
    expect(group?.items.map((item) => item.path)).toContain('/workflow-reports');
  });

  it('is reachable only with the report view key', () => {
    const viewer = staffMe('CUSTOMER_SUPPORT', ['moderation.report.view']);
    expect(reachableItems(viewer).map((item) => item.path)).toContain('/workflow-reports');
    expect(reachableItems(ADMIN_ME).map((item) => item.path)).toContain('/workflow-reports');
    const operator = staffMe('PLATFORM_OPERATOR', ['moderation.content.hide', 'workflow.run.view']);
    expect(reachableItems(operator).map((item) => item.path)).not.toContain('/workflow-reports');
    expect(
      reachableItems({ ...viewer, adminDisabled: true }).map((item) => item.path),
    ).not.toContain('/workflow-reports');
  });
});
