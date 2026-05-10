import type { ScopeEnum } from '../types/api';

interface Props {
    value: ScopeEnum;
    onChange: (next: ScopeEnum) => void;
}

/** Inline scope-picker для recurring-события в форме редактирования. */
export default function EditEventScopePicker({ value, onChange }: Props) {
    return (
        <div className="rounded-md border p-3 space-y-2"
             style={{ background: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
            <div className="text-xs flex items-center gap-1" style={{ color: 'var(--color-text-muted)' }}>
                <span style={{ color: 'var(--color-primary)' }}>↻</span>
                Это повторяющееся событие. Изменения применить к:
            </div>
            <div className="flex flex-col gap-1.5 text-sm">
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'THIS'} onChange={() => onChange('THIS')} />
                    Только к этому
                </label>
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'FOLLOWING'} onChange={() => onChange('FOLLOWING')} />
                    К этому и следующим
                </label>
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'ALL'} onChange={() => onChange('ALL')} />
                    Ко всем
                </label>
            </div>
        </div>
    );
}
