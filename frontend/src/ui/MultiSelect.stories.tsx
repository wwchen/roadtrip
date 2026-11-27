import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MultiSelect, type MultiSelectOption } from '@ui';

const OPTIONS: MultiSelectOption[] = [
  { value: 'usfs', label: 'USDA Forest Service', count: 21 },
  { value: 'csp', label: 'California State Parks', count: 14 },
  { value: 'nps', label: 'National Park Service', count: 9 },
  { value: 'blm', label: 'Bureau of Land Management', count: 6 },
];

const MANY: MultiSelectOption[] = Array.from({ length: 14 }, (_, i) => ({
  value: `option-${i}`,
  label: `Option ${i + 1}`,
  count: 40 - i * 2,
}));

function Demo({ options, initial = [] }: { options: MultiSelectOption[]; initial?: string[] }) {
  const [selected, setSelected] = useState<string[]>(initial);
  const toggle = (value: string) =>
    setSelected((current) => (current.includes(value) ? current.filter((v) => v !== value) : [...current, value]));
  const first = options.find((option) => selected.includes(option.value))?.label;
  const triggerText = !first ? 'All' : selected.length === 1 ? first : `${first} +${selected.length - 1}`;
  return (
    <div style={{ width: 320 }}>
      <MultiSelect
        id="demo"
        label="Agency"
        options={options}
        selected={selected}
        onToggle={toggle}
        onClear={() => setSelected([])}
        allLabel="All"
        triggerText={triggerText}
        searchLabel="Find an option"
        noMatchText="Nothing matches"
        emptyText="No options"
      />
    </div>
  );
}

const meta = {
  title: 'Ui/MultiSelect',
  parameters: {
    docs: {
      description: {
        component:
          'A field-shaped button that opens a checkbox list. Nothing selected means all; the caller ' +
          'orders the options and writes the trigger summary. Over ten options, the menu gets a search ' +
          'box. The menu sits right after its trigger in tab order and is positioned fixed, so a ' +
          'scrolling parent cannot clip it. It closes on an outside click, on Escape, or when focus ' +
          'leaves it.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const NothingSelected: Story = { render: () => <Demo options={OPTIONS} /> };
export const TwoSelected: Story = { render: () => <Demo options={OPTIONS} initial={['usfs', 'nps']} /> };
export const Searchable: Story = { render: () => <Demo options={MANY} /> };
