/**
 * Тема Tailwind для дизайн-системы — один источник для tailwind.config.js и для cn() из lib/utils.ts.
 *
 * Цвета, тени, скругления и высоты — переменные из ./tokens.css: они меняются с темой и правятся в Claude Design.
 * Имена переменных выписаны целиком, а не шаблоном: их видит сторож ANO-172 и находит поиск.
 * Шрифт и размеры текста — значения прямо здесь: от темы они не зависят.
 * Имена размеров текста не совпадают с именами цветов: иначе `text-sf-…` значило бы и размер, и цвет.
 */
type Size = [string, string | { lineHeight: string; letterSpacing?: string }];

const fontSize: Record<string, Size> = {
    'sf-caption': ['11px', '13px'],        // подписи в плитках и капсуле
    'sf-label': ['12px', '14px'],          // подпись над числом, заголовки дней
    'sf-note': ['13px', '18px'],           // чипы, галочки, кнопки на плашках, строка о «Свободно»
    'sf-control': ['14px', '17px'],        // переключатели, разделы, значения в плитках
    'sf-body': ['15px', '20px'],           // строки, поля, заголовки плашек
    'sf-button': ['16px', '20px'],         // кнопки формы
    'sf-number-s': ['27px', { lineHeight: '29px', letterSpacing: '-0.03em' }], // «Свободно» в шапке
    'sf-number-m': ['36px', { lineHeight: '40px', letterSpacing: '-0.03em' }], // поле суммы
    'sf-number-l': ['68px', { lineHeight: '1', letterSpacing: '-0.045em' }],   // «Свободно» на «Сегодня»
};

export const sfTheme = {
    colors: {
        sf: {
            page: 'var(--sf-page)',
            ink: 'var(--sf-ink)',
            muted: 'var(--sf-muted)',
            rule: 'var(--sf-rule)',
            chip: 'var(--sf-chip)',
            'chip-hover': 'var(--sf-chip-hover)',
            'chip-press': 'var(--sf-chip-press)',
            plate: 'var(--sf-plate)',
            'plate-hover': 'var(--sf-plate-hover)',
            tile: 'var(--sf-tile)',
            'tile-hover': 'var(--sf-tile-hover)',
            'tile-press': 'var(--sf-tile-press)',
            'switch-on': 'var(--sf-switch-on)',
            capsule: 'var(--sf-capsule)',
            scrim: 'var(--sf-scrim)',
            error: 'var(--sf-error)',
            accent: 'var(--sf-accent)',
            'accent-hover': 'var(--sf-accent-hover)',
            'accent-press': 'var(--sf-accent-press)',
            'on-accent': 'var(--sf-on-accent)',
            'accent-soft': 'var(--sf-accent-soft)',
            'accent-soft-hover': 'var(--sf-accent-soft-hover)',
            'accent-soft-press': 'var(--sf-accent-soft-press)',
            'kind-bron': 'var(--sf-kind-bron)',
            'kind-bron-bg': 'var(--sf-kind-bron-bg)',
            'kind-ozh': 'var(--sf-kind-ozh)',
            'kind-ozh-bg': 'var(--sf-kind-ozh-bg)',
            'kind-hot': 'var(--sf-kind-hot)',
            'kind-hot-bg': 'var(--sf-kind-hot-bg)',
        },
    },
    fontFamily: { sf: ['Geologica', 'system-ui', '-apple-system', '"Segoe UI"', 'Roboto', 'sans-serif'] },
    fontSize,
    borderRadius: {
        'sf-box': 'var(--sf-radius-box)',
        'sf-field': 'var(--sf-radius-field)',
        'sf-plate': 'var(--sf-radius-plate)',
        'sf-page': 'var(--sf-radius-page)',
        'sf-sheet': 'var(--sf-radius-sheet)',
        'sf-pill': 'var(--sf-radius-pill)',
    },
    boxShadow: {
        'sf-capsule': 'var(--sf-shadow-capsule)',
        'sf-raised': 'var(--sf-shadow-raised)',
    },
    // Высоты деталей — в шкале отступов Tailwind, чтобы работали h-, w- и size-: h-sf-tile, size-sf-button.
    spacing: {
        'sf-chip': 'var(--sf-h-chip)',
        'sf-control': 'var(--sf-h-control)',
        'sf-tile': 'var(--sf-h-tile)',
        'sf-button': 'var(--sf-h-button)',
        'sf-bar': 'var(--sf-h-bar)',
        'sf-gutter': 'var(--sf-gutter)',
    },
};
