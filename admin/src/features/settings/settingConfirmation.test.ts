import { loweringToConfirm } from './settingConfirmation';

const VERSIONS = 'media.editor.project_max_versions';

describe('loweringToConfirm', () => {
  it('asks when the version limit is saved lower', () => {
    expect(loweringToConfirm(VERSIONS, 50, 20)).toEqual({ from: 50, to: 20 });
  });

  it('asks when a reset lowers the version limit', () => {
    expect(loweringToConfirm(VERSIONS, 80, 50)).toEqual({ from: 80, to: 50 });
  });

  it('does not ask when the version limit is raised', () => {
    expect(loweringToConfirm(VERSIONS, 50, 60)).toBeNull();
  });

  it('does not ask for an equal value', () => {
    expect(loweringToConfirm(VERSIONS, 50, 50)).toBeNull();
  });

  it('does not ask for another key', () => {
    expect(loweringToConfirm('media.editor.batch_max_items', 4, 2)).toBeNull();
  });

  it('does not ask when either side is not a number', () => {
    expect(loweringToConfirm(VERSIONS, 50, null)).toBeNull();
    expect(loweringToConfirm(VERSIONS, 50, undefined)).toBeNull();
    expect(loweringToConfirm(VERSIONS, '50', 20)).toBeNull();
  });
});
