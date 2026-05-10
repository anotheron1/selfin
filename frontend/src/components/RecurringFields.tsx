import type { RecurringConfig, RecurringFrequency } from '../types/api';
import { Input } from './ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';

interface Props {
    enabled: boolean;
    value: RecurringConfig;
    eventDate: string;
    onToggle: (enabled: boolean) => void;
    onChange: (next: RecurringConfig) => void;
}

const RU_MONTHS = ['Январь','Февраль','Март','Апрель','Май','Июнь',
                   'Июль','Август','Сентябрь','Октябрь','Ноябрь','Декабрь'];

/**
 * Универсальный блок «Повторять» для формы создания/редактирования события.
 * Автоподставляет dayOfMonth / monthOfYear из eventDate при включении.
 */
export default function RecurringFields({ enabled, value, eventDate, onToggle, onChange }: Props) {
    const handleToggle = (e: React.ChangeEvent<HTMLInputElement>) => {
        const isOn = e.target.checked;
        if (isOn && eventDate) {
            const d = new Date(eventDate + 'T00:00:00');
            onChange({
                frequency: value.frequency ?? 'MONTHLY',
                dayOfMonth: value.dayOfMonth ?? d.getDate(),
                monthOfYear: value.frequency === 'YEARLY' ? (value.monthOfYear ?? d.getMonth() + 1) : null,
                endDate: value.endDate ?? null,
                startDate: eventDate,
            });
        }
        onToggle(isOn);
    };

    return (
        <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={enabled} onChange={handleToggle} />
                Повторять
            </label>

            {enabled && (
                <div className="space-y-2 pl-5 border-l-2 border-muted">
                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">Частота</span>
                        <Select
                            value={value.frequency}
                            onValueChange={(v: RecurringFrequency) => onChange({ ...value, frequency: v, monthOfYear: v === 'YEARLY' ? (value.monthOfYear ?? 1) : null })}
                        >
                            <SelectTrigger className="h-8 w-40 text-xs"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="MONTHLY">Ежемесячно</SelectItem>
                                <SelectItem value="YEARLY">Ежегодно</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">День</span>
                        <Input
                            type="number"
                            min={1} max={31}
                            value={value.dayOfMonth}
                            onChange={e => {
                                const raw = Number(e.target.value);
                                const clamped = Number.isNaN(raw) ? 1 : Math.min(31, Math.max(1, raw));
                                onChange({ ...value, dayOfMonth: clamped });
                            }}
                            className="h-8 w-20 text-xs"
                        />
                    </div>

                    {value.frequency === 'YEARLY' && (
                        <div className="flex items-center gap-2">
                            <span className="text-xs w-20">Месяц</span>
                            <Select
                                value={String(value.monthOfYear ?? 1)}
                                onValueChange={v => onChange({ ...value, monthOfYear: Number(v) })}
                            >
                                <SelectTrigger className="h-8 w-40 text-xs"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    {RU_MONTHS.map((name, idx) => (
                                        <SelectItem key={idx} value={String(idx + 1)}>{name}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    )}

                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">Окончание</span>
                        <label className="text-xs flex items-center gap-1">
                            <input type="radio" name="endMode"
                                checked={value.endDate == null}
                                onChange={() => onChange({ ...value, endDate: null })} />
                            Бессрочно
                        </label>
                        <label className="text-xs flex items-center gap-1">
                            <input type="radio" name="endMode"
                                checked={value.endDate != null}
                                onChange={() => onChange({ ...value, endDate: eventDate })} />
                            До даты
                        </label>
                        {value.endDate != null && (
                            <Input type="date"
                                value={value.endDate}
                                onChange={e => onChange({ ...value, endDate: e.target.value })}
                                className="h-8 text-xs" />
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
