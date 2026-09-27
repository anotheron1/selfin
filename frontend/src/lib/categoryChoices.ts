import type { Category } from '../types/api';

type CategoryChoice = Pick<Category, 'id' | 'name'>;
type EventCategory = { categoryId?: string | null; categoryName?: string | null };

export function categoryChoices(
    categories: readonly CategoryChoice[] | null,
    event: EventCategory,
): readonly CategoryChoice[] {
    const choices = categories ?? [];
    if (!event.categoryId || choices.some(category => category.id === event.categoryId)) {
        return choices;
    }
    return [...choices, {
        id: event.categoryId,
        name: `${event.categoryName ?? ''}${categories === null ? '' : ' (в архиве)'}`,
    }];
}
