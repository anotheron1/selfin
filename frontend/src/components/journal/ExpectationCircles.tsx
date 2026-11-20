import type { ReactNode } from 'react';
import type { ExpectationGroup } from '../../lib/journalSections';
import { ExpectationMark } from './ExpectationSheet';

/**
 * Кружки ожиданий на телефоне (срез 3, п. 10; доска Zh2-J8): кружок с «+» — форма записи с категорией ожидания,
 * подпись под ним — лист строк по датам. Без цвета превышения и без полосы — граница по запрету 1.
 */
export default function ExpectationCircles({ groups, onRecord, onOpen, hint }: {
    groups: ExpectationGroup[];
    onRecord: (g: ExpectationGroup) => void;
    onOpen: (g: ExpectationGroup) => void;
    /** Пузырь первого журнала у первого кружка (S3-N1). */
    hint?: ReactNode;
}) {
    return (
        <div className="relative">
            <div className="scrollbar-none -mx-4 flex gap-2 overflow-x-auto px-4">
                {groups.map((g) => (
                    <div key={g.key} className="flex w-[104px] shrink-0 flex-col items-center">
                        <button type="button" aria-label={`Записать: ${g.title}`} onClick={() => onRecord(g)}
                            className="rounded-sf-pill transition-transform active:scale-95">
                            <ExpectationMark group={g} size="l" plus />
                        </button>
                        <button type="button" onClick={() => onOpen(g)} className="mt-1 flex w-full flex-col items-center text-center">
                            <span className="w-full truncate text-sf-label font-semibold">{g.title}</span>
                            <span className="text-sf-caption text-sf-muted">{g.caption}</span>
                        </button>
                    </div>
                ))}
            </div>
            <p className="mt-1.5 text-sf-label text-sf-muted">Нажми на кружок — запись к ожиданию, на подпись — строки по датам.</p>
            {hint}
        </div>
    );
}
