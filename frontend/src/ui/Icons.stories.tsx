import type { Meta, StoryObj } from '@storybook/react-vite';
import { ICON_NAMES } from '@lew-ds/open-icons';
import spriteMarkup from '@lew-ds/open-icons/icons.svg?raw';
import { Icon } from '@ui';

/**
 * The symbols whose artwork is cut by a `<mask>` or `<clipPath>` rather than
 * drawn — the ones that render broken when the sprite is referenced across
 * files. Derived from the sprite rather than listed, so the catalog stays honest
 * about which symbols these are when the set is re-exported.
 */
const MASKED_ICON_NAMES = ICON_NAMES.filter((name) => {
  const symbol = spriteMarkup.match(new RegExp(`<symbol[^>]*id="${name}"(.*?)</symbol>`, 's'));
  return symbol !== null && symbol[1].includes('url(#');
});

const meta = {
  title: 'Design System/Icons',
  parameters: {
    docs: {
      description: {
        component:
          'Open Icons rendered through LDS. The masked set is the regression surface: referenced ' +
          'across files those symbols render blank or lose half their artwork, because an external ' +
          '`<use>` resolves a mask id against the host document. See `@ui/icon-sprite`.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function IconGrid({ names }: { names: readonly string[] }) {
  return (
    <ul className="rt-icon-grid">
      {names.map((name) => (
        <li className="rt-icon-grid__item" key={name}>
          <Icon name={name} className="rt-icon-grid__glyph" />
          <code className="rt-icon-grid__name">{name}</code>
        </li>
      ))}
    </ul>
  );
}

/**
 * Every symbol whose artwork is masked. A blank tile, or a glyph missing half its
 * strokes, means the sprite is being referenced across files again.
 */
export const Masked: Story = {
  render: () => <IconGrid names={MASKED_ICON_NAMES} />,
};

/** The whole set, for picking a name. */
export const All: Story = {
  render: () => <IconGrid names={ICON_NAMES} />,
};
