import { useCallback, useLayoutEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type RefObject } from 'react';
import { Checkbox, Icon, TextField } from '@lew-ds/lds-react';
import { useDismiss } from '@/lib/use-dismiss';
import './multi-select.css';

export const MULTI_SELECT_SEARCH_THRESHOLD = 10;
const MENU_GAP_PX = 4;
const MENU_MIN_WIDTH_PX = 260;
const VIEWPORT_MARGIN_PX = 8;

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
  triggerText: string;
  searchLabel: string;
  noMatchText: string;
  emptyText: string;
  defaultOpen?: boolean;
}

interface MenuPosition {
  top: number;
  left: number;
  width: number;
  maxHeight: number;
}

function useAnchoredPosition(anchor: HTMLElement | null, open: boolean): MenuPosition | null {
  const [position, setPosition] = useState<MenuPosition | null>(null);
  useLayoutEffect(() => {
    if (!open || !anchor) return;
    const measure = () => {
      const rect = anchor.getBoundingClientRect();
      const top = rect.bottom + MENU_GAP_PX;
      setPosition({
        top,
        left: rect.left,
        width: Math.max(rect.width, MENU_MIN_WIDTH_PX),
        maxHeight: window.innerHeight - top - VIEWPORT_MARGIN_PX,
      });
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
  triggerText,
  searchLabel,
  noMatchText,
  emptyText,
  defaultOpen = false,
}: MultiSelectProps) {
  const [open, setOpen] = useState(defaultOpen);
  const [query, setQuery] = useState('');
  const [trigger, setTrigger] = useState<HTMLButtonElement | null>(null);
  const fieldRef = useRef<HTMLDivElement | null>(null);
  const position = useAnchoredPosition(trigger, open);
  const close = useCallback(() => setOpen(false), []);

  const labelId = `${id}-label`;
  const valueId = `${id}-value`;
  const menuId = `${id}-menu`;
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

  const onBlur = (event: FocusEvent<HTMLDivElement>) => {
    const next = event.relatedTarget as Node | null;
    if (next && fieldRef.current?.contains(next)) return;
    if (next) setOpen(false);
  };

  return (
    <div className="rt-field" ref={fieldRef} onKeyDown={onKeyDown} onBlur={onBlur}>
      <span className="rt-field__label" id={labelId}>
        {label}
      </span>
      <button
        ref={setTrigger}
        id={id}
        type="button"
        className={open ? 'rt-field-box rt-field-box--open' : 'rt-field-box'}
        aria-labelledby={`${labelId} ${valueId}`}
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
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
      {open ? (
        <MultiSelectMenu
          id={menuId}
          fieldRef={fieldRef}
          label={label}
          position={position}
          onDismiss={close}
          searchable={searchable}
          searchLabel={searchLabel}
          query={query}
          onQuery={setQuery}
          allLabel={allLabel}
          noneSelected={selected.length === 0}
          onClear={onClear}
          rows={rows}
          picked={picked}
          onToggle={onToggle}
          emptyText={needle ? noMatchText : emptyText}
        />
      ) : null}
    </div>
  );
}

interface MultiSelectMenuProps {
  id: string;
  fieldRef: RefObject<HTMLDivElement | null>;
  label: string;
  position: MenuPosition | null;
  onDismiss: () => void;
  searchable: boolean;
  searchLabel: string;
  query: string;
  onQuery: (query: string) => void;
  allLabel: string;
  noneSelected: boolean;
  onClear: () => void;
  rows: readonly MultiSelectOption[];
  picked: ReadonlySet<string>;
  onToggle: (value: string) => void;
  emptyText: string;
}

function MultiSelectMenu({
  id,
  fieldRef,
  label,
  position,
  onDismiss,
  searchable,
  searchLabel,
  query,
  onQuery,
  allLabel,
  noneSelected,
  onClear,
  rows,
  picked,
  onToggle,
  emptyText,
}: MultiSelectMenuProps) {
  useDismiss(fieldRef, onDismiss);

  return (
    <div id={id} className="lds-menu rt-multi-select__menu" role="group" aria-label={label} style={position ?? undefined}>
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
        {rows.length === 0 ? <div className="rt-multi-select__empty">{emptyText}</div> : null}
      </div>
    </div>
  );
}
