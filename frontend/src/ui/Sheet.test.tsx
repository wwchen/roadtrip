import { afterEach, describe, expect, test, vi } from 'vitest';
import { createEvent, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Sheet, SheetHeader, SheetSection, SHEET_DETENTS, type SheetDetent } from './Sheet';

const noop = () => {};

const LABEL = 'Upper Pines';
const SCREEN_HEIGHT = 1000;
const DETENT_RATIOS = { peek: 0.2, half: 0.5, full: 0.9 } as const;
const DETENT_STYLE = {
  '--rt-sheet-peek': String(DETENT_RATIOS.peek),
  '--rt-sheet-half': String(DETENT_RATIOS.half),
  '--rt-sheet-full': String(DETENT_RATIOS.full),
} as React.CSSProperties;
const GRABBER_Y = 800;
const TOP_CLEARANCE_PX = 300;
const TOP_GAP_PX = 8;
const CLEARED_STYLE = {
  ...DETENT_STYLE,
  '--rt-sheet-top-clearance': `${TOP_CLEARANCE_PX}px`,
  '--rt-sheet-top-gap': `${TOP_GAP_PX}px`,
} as React.CSSProperties;

const grabber = () => screen.getByRole('button', { name: /upper pines/i });

const mountDraggable = (detent: SheetDetent) => {
  const onDetentChange = vi.fn();
  render(
    <Sheet detent={detent} onDetentChange={onDetentChange} label={LABEL} style={DETENT_STYLE} data-testid="sheet" />,
  );
  return onDetentChange;
};

const drag = (toY: number) => {
  fireEvent.pointerDown(grabber(), { pointerId: 1, clientY: GRABBER_Y, button: 0 });
  fireEvent.pointerMove(grabber(), { pointerId: 1, clientY: toY });
  fireEvent.pointerUp(grabber(), { pointerId: 1, clientY: toY });
};

describe('Sheet', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test('renders a section at or below the current detent', () => {
    render(
      <Sheet detent="half" onDetentChange={noop} label="Upper Pines">
        <SheetSection from="peek">verdict</SheetSection>
        <SheetSection from="half">nights</SheetSection>
      </Sheet>,
    );
    expect(screen.getByText('verdict')).toBeInTheDocument();
    expect(screen.getByText('nights')).toBeInTheDocument();
  });

  test('does not render a section above the current detent', () => {
    render(
      <Sheet detent="half" onDetentChange={noop} label="Upper Pines">
        <SheetSection from="full">specs</SheetSection>
      </Sheet>,
    );
    expect(screen.queryByText('specs')).not.toBeInTheDocument();
  });

  test('finds the detent through consumer markup between the sheet and its section', () => {
    render(
      <Sheet detent="peek" onDetentChange={noop} label="Upper Pines">
        <div>
          <SheetSection from="half">nights</SheetSection>
        </div>
      </Sheet>,
    );
    expect(screen.queryByText('nights')).not.toBeInTheDocument();
  });

  test('the header compacts from half upward', () => {
    const { rerender } = render(
      <Sheet detent="peek" onDetentChange={noop} label="Upper Pines">
        <SheetHeader full={<span>full header</span>} compact={<span>compact header</span>} />
      </Sheet>,
    );
    expect(screen.getByText('full header')).toBeInTheDocument();
    rerender(
      <Sheet detent="half" onDetentChange={noop} label="Upper Pines">
        <SheetHeader full={<span>full header</span>} compact={<span>compact header</span>} />
      </Sheet>,
    );
    expect(screen.getByText('compact header')).toBeInTheDocument();
    expect(screen.queryByText('full header')).not.toBeInTheDocument();
  });

  test('the header can hold its full form until a later detent', () => {
    render(
      <Sheet detent="half" onDetentChange={noop} label="Upper Pines">
        <SheetHeader full={<span>full header</span>} compact={<span>compact header</span>} compactFrom="full" />
      </Sheet>,
    );
    expect(screen.getByText('full header')).toBeInTheDocument();
  });

  test('the grabber raises and lowers the detent by keyboard', async () => {
    const onDetentChange = vi.fn();
    render(<Sheet detent="peek" onDetentChange={onDetentChange} label="Upper Pines" />);
    await userEvent.type(screen.getByRole('button', { name: /upper pines/i }), '{ArrowUp}');
    expect(onDetentChange).toHaveBeenCalledWith('half');
  });

  test('arrow keys step one detent and stop at the ends', () => {
    const onDetentChange = vi.fn();
    const { rerender } = render(<Sheet detent="half" onDetentChange={onDetentChange} label={LABEL} />);

    fireEvent.keyDown(grabber(), { key: 'ArrowUp' });
    expect(onDetentChange).toHaveBeenLastCalledWith('full');
    fireEvent.keyDown(grabber(), { key: 'ArrowDown' });
    expect(onDetentChange).toHaveBeenLastCalledWith('peek');

    onDetentChange.mockClear();
    rerender(<Sheet detent="full" onDetentChange={onDetentChange} label={LABEL} />);
    fireEvent.keyDown(grabber(), { key: 'ArrowUp' });
    rerender(<Sheet detent="peek" onDetentChange={onDetentChange} label={LABEL} />);
    fireEvent.keyDown(grabber(), { key: 'ArrowDown' });
    expect(onDetentChange).not.toHaveBeenCalled();
  });

  test('arrow keys are handled so the page behind does not scroll', () => {
    render(<Sheet detent="half" onDetentChange={noop} label={LABEL} />);
    const arrow = createEvent.keyDown(grabber(), { key: 'ArrowUp' });
    fireEvent(grabber(), arrow);
    expect(arrow.defaultPrevented).toBe(true);
  });

  test('other keys are left alone', () => {
    const onDetentChange = vi.fn();
    render(<Sheet detent="half" onDetentChange={onDetentChange} label={LABEL} />);
    const tab = createEvent.keyDown(grabber(), { key: 'Tab' });
    fireEvent(grabber(), tab);
    expect(tab.defaultPrevented).toBe(false);
    expect(onDetentChange).not.toHaveBeenCalled();
  });

  test('the grabber is named for the sheet and says whether it is open', () => {
    const { rerender } = render(<Sheet detent="peek" onDetentChange={noop} label={LABEL} />);
    expect(grabber()).toHaveAttribute('aria-expanded', 'false');
    rerender(<Sheet detent="half" onDetentChange={noop} label={LABEL} />);
    expect(grabber()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('region', { name: LABEL })).toBeInTheDocument();
  });

  test('tapping the grabber raises one detent, and from full returns to peek', async () => {
    const onDetentChange = vi.fn();
    const { rerender } = render(<Sheet detent="peek" onDetentChange={onDetentChange} label={LABEL} />);
    await userEvent.click(grabber());
    expect(onDetentChange).toHaveBeenLastCalledWith('half');

    rerender(<Sheet detent="full" onDetentChange={onDetentChange} label={LABEL} />);
    await userEvent.click(grabber());
    expect(onDetentChange).toHaveBeenLastCalledWith('peek');
  });

  test('carries the detent and the consumer’s own props on its root', () => {
    render(<Sheet detent="half" onDetentChange={noop} label={LABEL} data-testid="place-sheet" className="mine" />);
    const root = screen.getByTestId('place-sheet');
    expect(root).toHaveAttribute('data-detent', 'half');
    expect(root).toHaveClass('rt-sheet', 'mine');
  });

  test('a sheet given a dismissal offers a close control that is a real touch target', async () => {
    const onClose = vi.fn();
    render(<Sheet detent="full" onDetentChange={noop} label={LABEL} onClose={onClose} />);

    await userEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Escape inside the sheet dismisses it', () => {
    const onClose = vi.fn();
    render(
      <Sheet detent="full" onDetentChange={noop} label={LABEL} onClose={onClose}>
        <SheetSection from="peek">body</SheetSection>
      </Sheet>,
    );

    fireEvent.keyDown(screen.getByText('body'), { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('a sheet with nowhere to close to draws no close control and ignores Escape', () => {
    render(<Sheet detent="full" onDetentChange={noop} label={LABEL} data-testid="sheet" />);

    expect(screen.queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
    const escape = createEvent.keyDown(screen.getByTestId('sheet'), { key: 'Escape' });
    fireEvent(screen.getByTestId('sheet'), escape);
    expect(escape.defaultPrevented).toBe(false);
  });

  test('the scrolling body is a tab stop, so a keyboard can reach prose with no controls in it', () => {
    const { container } = render(
      <Sheet detent="full" onDetentChange={noop} label={LABEL}>
        <SheetSection from="peek">body</SheetSection>
      </Sheet>,
    );

    expect(container.querySelector('.rt-sheet-body')).toHaveAttribute('tabindex', '0');
  });

  test('lists the detents in ascending order', () => {
    expect(SHEET_DETENTS).toEqual(['peek', 'half', 'full']);
  });

  describe('dragging the grabber', () => {
    test('commits to the nearest detent on release', () => {
      const onDetentChange = mountDraggable('peek');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      drag(GRABBER_Y - SCREEN_HEIGHT * (DETENT_RATIOS.half - DETENT_RATIOS.peek));

      expect(onDetentChange).toHaveBeenCalledTimes(1);
      expect(onDetentChange).toHaveBeenCalledWith('half');
    });

    test('skips a detent when the drag travels past it', () => {
      const onDetentChange = mountDraggable('peek');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      drag(GRABBER_Y - SCREEN_HEIGHT);

      expect(onDetentChange).toHaveBeenCalledWith('full');
    });

    test('never drags past the room left below the topbar', () => {
      render(
        <Sheet detent="peek" onDetentChange={noop} label={LABEL} style={CLEARED_STYLE} data-testid="sheet" />,
      );
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      fireEvent.pointerDown(grabber(), { pointerId: 1, clientY: GRABBER_Y, button: 0 });
      fireEvent.pointerMove(grabber(), { pointerId: 1, clientY: GRABBER_Y - SCREEN_HEIGHT });

      expect(screen.getByTestId('sheet').style.getPropertyValue('--rt-sheet-drag-height')).toBe(
        `${SCREEN_HEIGHT - TOP_CLEARANCE_PX - TOP_GAP_PX}px`,
      );
    });

    test('lowers the sheet when dragged down', () => {
      const onDetentChange = mountDraggable('full');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      drag(GRABBER_Y + SCREEN_HEIGHT);

      expect(onDetentChange).toHaveBeenCalledWith('peek');
    });

    test('reports nothing when the drag settles back on the same detent', () => {
      const onDetentChange = mountDraggable('half');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      drag(GRABBER_Y - SCREEN_HEIGHT * 0.05);

      expect(onDetentChange).not.toHaveBeenCalled();
    });

    test('marks the sheet as dragging, and only while the pointer is down', () => {
      mountDraggable('peek');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);
      const root = screen.getByTestId('sheet');

      fireEvent.pointerDown(grabber(), { pointerId: 1, clientY: GRABBER_Y, button: 0 });
      expect(root).not.toHaveAttribute('data-dragging');
      fireEvent.pointerMove(grabber(), { pointerId: 1, clientY: GRABBER_Y - SCREEN_HEIGHT * 0.3 });
      expect(root).toHaveAttribute('data-dragging');
      fireEvent.pointerUp(grabber(), { pointerId: 1, clientY: GRABBER_Y - SCREEN_HEIGHT * 0.3 });
      expect(root).not.toHaveAttribute('data-dragging');
    });

    test('a cancelled drag commits nothing', () => {
      const onDetentChange = mountDraggable('peek');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      fireEvent.pointerDown(grabber(), { pointerId: 1, clientY: GRABBER_Y, button: 0 });
      fireEvent.pointerMove(grabber(), { pointerId: 1, clientY: 0 });
      fireEvent.pointerCancel(grabber(), { pointerId: 1 });

      expect(onDetentChange).not.toHaveBeenCalled();
      expect(screen.getByTestId('sheet')).not.toHaveAttribute('data-dragging');
    });

    test('the click that ends a drag does not also step the detent', () => {
      const onDetentChange = mountDraggable('peek');
      vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(SCREEN_HEIGHT);

      drag(GRABBER_Y - SCREEN_HEIGHT * (DETENT_RATIOS.full - DETENT_RATIOS.peek));
      fireEvent.click(grabber());

      expect(onDetentChange).toHaveBeenCalledTimes(1);
      expect(onDetentChange).toHaveBeenCalledWith('full');
    });
  });
});
