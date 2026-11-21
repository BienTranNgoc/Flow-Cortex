import { parseRunFilterParams, withRunFilterParams } from './runFilterParams';

const WORKFLOW = '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b';

describe('run filter params', () => {
  it('reads every filter the address carries', () => {
    const params = new URLSearchParams(`status=error&workflowId=${WORKFLOW}&from=2026-11-02&to=2026-11-03`);

    expect(parseRunFilterParams(params)).toEqual({
      status: 'error',
      workflowId: WORKFLOW,
      from: '2026-11-02',
      to: '2026-11-03',
    });
  });

  it('drops a value that could not be a valid filter', () => {
    const params = new URLSearchParams('status=paused&workflowId=abc&from=2026-02-30&to=soon');

    expect(parseRunFilterParams(params)).toEqual({
      status: undefined,
      workflowId: undefined,
      from: undefined,
      to: undefined,
    });
  });

  it('sets, replaces and removes keys without touching the others', () => {
    const next = withRunFilterParams(new URLSearchParams('status=error&from=2026-11-02'), {
      status: '',
      workflowId: WORKFLOW,
    });

    expect(next.has('status')).toBe(false);
    expect(next.get('workflowId')).toBe(WORKFLOW);
    expect(next.get('from')).toBe('2026-11-02');
  });
});
