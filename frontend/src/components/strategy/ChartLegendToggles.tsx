interface Toggle {
    id: string;
    label: string;
    color: string;       // CSS color value для маркера
    shape?: 'dot' | 'bar' | 'dash';
    active: boolean;
    onToggle?: () => void;   // если не задан — toggle не работает (для "Always on" линий)
}

interface Props {
    title: string;
    toggles: Toggle[];
}

export default function ChartLegendToggles({ title, toggles }: Props) {
    return (
        <div className="flex flex-wrap items-center gap-2 mb-2">
            <span className="text-sm font-semibold mr-2">{title}</span>
            {toggles.map(t => (
                <button
                    key={t.id}
                    type="button"
                    onClick={t.onToggle}
                    disabled={!t.onToggle}
                    className="inline-flex items-center gap-1.5 text-xs px-2 py-1 rounded-full border transition-opacity"
                    style={{
                        background: t.active ? `${t.color}26` : 'transparent',
                        borderColor: t.active ? `${t.color}80` : 'var(--color-border)',
                        color: t.active ? 'inherit' : 'var(--color-text-muted)',
                        cursor: t.onToggle ? 'pointer' : 'default',
                    }}
                >
                    {t.shape === 'dash'
                        ? <span style={{ display: 'inline-block', width: 12, height: 1, background: t.color }} />
                        : t.shape === 'bar'
                        ? <span style={{ display: 'inline-block', width: 8, height: 8, background: t.color, borderRadius: 2 }} />
                        : <span style={{ display: 'inline-block', width: 8, height: 8, background: t.color, borderRadius: '50%' }} />}
                    {t.label}
                </button>
            ))}
        </div>
    );
}
