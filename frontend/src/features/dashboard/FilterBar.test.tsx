import { cleanup, render, screen } from '@testing-library/react';
import i18n from '../../lib/i18n';
import { FilterBar } from './FilterBar';

const COUNTS = { all: 3, video: 1, audio: 0, demo: 2, production: 0 };

describe('FilterBar', () => {
  afterEach(async () => {
    cleanup();
    await i18n.changeLanguage('en');
  });

  it('says "workflow" for one and "workflows" for several in English', () => {
    render(<FilterBar value="all" onChange={() => {}} counts={COUNTS} />);

    expect(screen.getByText('1 workflow')).toBeInTheDocument();
    expect(screen.getByText('2 workflows')).toBeInTheDocument();
    expect(screen.getByText('3 workflows')).toBeInTheDocument();
  });

  it('keeps the same noun for every count in Vietnamese', async () => {
    await i18n.changeLanguage('vi');
    render(<FilterBar value="all" onChange={() => {}} counts={COUNTS} />);

    expect(screen.getByText('1 quy trình')).toBeInTheDocument();
    expect(screen.getByText('2 quy trình')).toBeInTheDocument();
  });
});
