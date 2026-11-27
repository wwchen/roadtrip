import { useCallback, useLayoutEffect, useRef, useState, type KeyboardEvent, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Checkbox, Icon, TextField } from '@lew-ds/lds-react';
import { useDismiss } from '@/lib/use-dismiss';
import './multi-select.css';

export const MULTI_SELECT_SEARCH_THRESHOLD = 10;
const MENU_GAP_PX = 4;
const MENU_MIN_WIDTH_PX = 260;

export interface MultiSelectOption {
  value: string;
  label: string;
  count?: number;
}

export interface MultiSelectProps {
  id: string;
  label: string;
  options: readonly MultiSelectOption[];
  selected: readonly string[];
  onToggle: (value: string) => void;
  onClear: () => void;
  allLabel: string;
  allCount?: number;
  triggerText: string;
  searchLabel: string;
  noMatchText: string;
  defaultOpen?: boolean;
}

interface MenuPosition {
  top: number;
  left: number;
  width: number;
}

function useAnchoredPosition(anchor: HTMLElement | null, open: boolean): MenuPosition | null {
  const [position, setPosition] = useState<MenuPosition | null>(null);
  useLayoutEffect(() => {
    if (!open || !anchor) return;
    const measure = () => {
      const rect = anchor.getBoundingClientRect();
      setPosition({ top: rect.bottom + MENU_GAP_PX, left: rect.left, width: Math.max(rect.width, MENU_MIN_WIDTH_PX) });
    };
    measure();
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    return () => {
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
    };
  }, [anchor, open]);
  return position;
}

export function MultiSelect({
  id,
  label,
  options,
  selected,
  onToggle,
  onClear,
  allLabel,
  allCount,
  triggerText,
  searchLabel,
  noMatchText,
  defaultOpen = false,
}: MultiSelectProps) {
  const [open, setOpen] = useState(defaultOpen);
  const [query, setQuery] = useState('');
  const [trigger, setTrigger] = useState<HTMLButtonElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const position = useAnchoredPosition(trigger, open);
  const close = useCallback(() => setOpen(false), []);

  const labelId = `${id}-label`;
  const valueId = `${id}-value`;
  const picked = new Set(selected);
  const needle = query.trim().toLowerCase();
  const rows = needle ? options.filter((option) => option.label.toLowerCase().includes(needle)) : options;
  const searchable = options.length > MULTI_SELECT_SEARCH_THRESHOLD;

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Escape' || !open) return;
    event.stopPropagation();
    setOpen(false);
    trigger?.focus();
  };

  return (
    <div className="rt-field" onKeyDown={onKeyDown}>
      <span className="rt-field__label" id={labelId}>
        {label}
      </span>
      <button
        ref={setTrigger}
        id={id}
        type="button"
        className={open ? 'rt-field-box rt-field-box--open' : 'rt-field-box'}
        aria-labelledby={`${labelId} ${valueId}`}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span
          id={valueId}
          className={selected.length === 0 ? 'rt-field-box__value rt-field-box__placeholder' : 'rt-field-box__value'}
        >
          {triggerText}
        </span>
        <Icon name={open ? 'chevron-up' : 'chevron-down'} aria-hidden="true" />
      </button>
      {open
        ? createPortal(
            <MultiSelectMenu
              menuRef={menuRef}
              label={label}
              position={position}
              onDismiss={close}
              searchable={searchable}
              searchLabel={searchLabel}
              query={query}
              onQuery={setQuery}
              allLabel={allLabel}
              allCount={allCount}
              noneSelected={selected.length === 0}
              onClear={onClear}
              rows={rows}
              picked={picked}
              onToggle={onToggle}
              noMatchText={noMatchText}
            />,
            document.body,
          )
        : null}
    </div>
  );
}

interface MultiSelectMenuProps {
  menuRef: RefObject<HTMLDivElement | null>;
  label: string;
  position: MenuPosition | null;
  onDismiss: () => void;
  searchable: boolean;
  searchLabel: string;
  query: string;
  onQuery: (query: string) => void;
  allLabel: string;
  allCount?: number;
  noneSelected: boolean;
  onClear: () => void;
  rows: readonly MultiSelectOption[];
  picked: ReadonlySet<string>;
  onToggle: (value: string) => void;
  noMatchText: string;
}

function MultiSelectMenu({
  menuRef,
  label,
  position,
  onDismiss,
  searchable,
  searchLabel,
  query,
  onQuery,
  allLabel,
  allCount,
  noneSelected,
  onClear,
  rows,
  picked,
  onToggle,
  noMatchText,
}: MultiSelectMenuProps) {
  useDismiss(menuRef, onDismiss);

  return (
    <div
      ref={menuRef}
      className="lds-menu rt-multi-select__menu"
      role="group"
      aria-label={label}
      style={position ?? undefined}
    >
      {searchable ? (
        <div className="rt-multi-select__search">
          <TextField
            placeholder={searchLabel}
            aria-label={searchLabel}
            value={query}
            onChange={(event) => onQuery(event.target.value)}
            autoFocus
          />
        </div>
      ) : null}
      <div className="rt-multi-select__row rt-multi-select__row--all">
        <Checkbox checked={noneSelected} onChange={onClear} label={allLabel} />
        {allCount != null ? <span className="rt-multi-select__count">{allCount.toLocaleString()}</span> : null}
      </div>
      <hr className="lds-menu__separator" />
      <div className="rt-multi-select__list">
        {rows.map((option) => (
          <div key={option.value} className="rt-multi-select__row">
            <Checkbox checked={picked.has(option.value)} onChange={() => onToggle(option.value)} label={option.label} />
            {option.count != null ? (
              <span className="rt-multi-select__count">{option.count.toLocaleString()}</span>
            ) : null}
          </div>
        ))}
        {rows.length === 0 ? <div className="rt-multi-select__empty">{noMatchText}</div> : null}
      </div>
    </div>
  );
}
