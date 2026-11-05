import { render } from '@testing-library/react';
import { NodiPeek } from './NodiPeek';
import { NodiMark } from './NodiMark';

describe('NodiPeek', () => {
  it('puts the pose on data-pose of a hidden svg', () => {
    const { container } = render(<NodiPeek pose="worried" />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('data-pose', 'worried');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('NodiMark', () => {
  it('renders a hidden svg at the requested size', () => {
    const { container } = render(<NodiMark size={34} />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('width', '34');
  });
});
