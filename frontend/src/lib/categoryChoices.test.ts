import { describe, expect, it } from 'vitest';
import { categoryChoices } from './categoryChoices';

const active = [{ id: 'food', name: 'Продукты' }, { id: 'car', name: 'Авто' }];

describe('categoryChoices', () => {
    it('keeps active choices and uses the current name, matching by id', () => {
        expect(categoryChoices(active, { categoryId: 'food', categoryName: 'Старое имя' }))
            .toEqual(active);
    });

    it('appends the event category as archived without changing the active list', () => {
        expect(categoryChoices(active, { categoryId: 'old', categoryName: 'Авто' }))
            .toEqual([...active, { id: 'old', name: 'Авто (в архиве)' }]);
        expect(active).toHaveLength(2);
    });

    it('shows the archived category even when there are no active categories', () => {
        expect(categoryChoices([], { categoryId: 'old', categoryName: 'Кружок' }))
            .toEqual([{ id: 'old', name: 'Кружок (в архиве)' }]);
    });

    it.each([null, undefined, ''])('does not add a choice without a category id (%s)', categoryId => {
        expect(categoryChoices(active, { categoryId, categoryName: 'Кружок' })).toEqual(active);
    });

    it('shows the current name without claiming it is archived before categories load', () => {
        expect(categoryChoices(null, { categoryId: 'food', categoryName: 'Продукты' }))
            .toEqual([{ id: 'food', name: 'Продукты' }]);
        expect(categoryChoices(null, {})).toEqual([]);
    });
});
