import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { COPIED_STATE_MS } from '@/lib/share-links';
import { SharePoiButton } from './fields';
import { ChargerPoiPage } from './types/charger';

const ORIGIN = window.location.origin;
const COPY_LABEL = 'Copy link to this place';
const COPIED_LABEL = 'Link copied';

let writeText: ReturnType<typeof vi.fn>;
let execCommand: ReturnType<typeof vi.fn>;

/** Swap the async clipboard in and out — the insecure-context case has none. */
const setClipboard = (clipboard: unknown) =>
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });

beforeEach(() => {
  window.history.replaceState(null, '', '/');
  writeText = vi.fn(async () => {});
  execCommand = vi.fn(() => true);
  setClipboard({ writeText });
  Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true });
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  setClipboard(undefined);
});

const press = async (name: string) => {
  await act(async () => {
    screen.getByLabelText(name).click();
  });
};

describe('SharePoiButton', () => {
  test('copies this place’s deep link and says so, briefly', async () => {
    render(<SharePoiButton id={4242} />);

    await press(COPY_LABEL);

    expect(writeText).toHaveBeenCalledWith(`${ORIGIN}/?poi=4242`);
    expect(screen.getByLabelText(COPIED_LABEL)).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(COPIED_STATE_MS);
    });
    expect(screen.getByLabelText(COPY_LABEL)).toBeInTheDocument();
  });

  test('does not inherit the query of the link that opened the tab', async () => {
    window.history.replaceState(null, '', '/?poi=1&route=abc');
    render(<SharePoiButton id={99} />);

    await press(COPY_LABEL);

    expect(writeText).toHaveBeenCalledWith(`${ORIGIN}/?poi=99`);
  });

  // The whole reason the copy goes through `copyShareUrl`: `navigator.clipboard` is
  // absent in a non-secure context, which is where the smoke suite reads this.
  test('still copies where there is no async clipboard', async () => {
    setClipboard(undefined);
    render(<SharePoiButton id={7} />);

    await press(COPY_LABEL);

    expect(execCommand).toHaveBeenCalledWith('copy');
    expect(screen.getByLabelText(COPIED_LABEL)).toBeInTheDocument();
  });

  test('a copy that failed does not claim to have worked', async () => {
    setClipboard(undefined);
    execCommand.mockImplementation(() => {
      throw new Error('nope');
    });
    render(<SharePoiButton id={7} />);

    await press(COPY_LABEL);

    expect(screen.queryByLabelText(COPIED_LABEL)).toBeNull();
    expect(screen.getByLabelText(COPY_LABEL)).toBeInTheDocument();
  });

  test('an unidentified place has no link to hand out', () => {
    render(<SharePoiButton id={null} />);

    expect(screen.queryByLabelText(COPY_LABEL)).toBeNull();
  });
});

describe('the actions row', () => {
  test('carries the share button on a real type page', () => {
    render(
      <ChargerPoiPage
        variant="page"
        feature={
          {
            type: 'Feature',
            id: 51,
            geometry: { type: 'Point', coordinates: [-119.59, 37.74] },
            properties: { category: 'tesla_supercharger', name: 'Yosemite Supercharger' },
          } as FlatPoiFeature
        }
      />,
    );

    expect(screen.getByLabelText(COPY_LABEL)).toBeInTheDocument();
  });
});
