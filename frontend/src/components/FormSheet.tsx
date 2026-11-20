import * as Dialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { IconButton } from '@/ds';

/**
 * Лист формы записи: на телефоне — снизу во всю ширину (Zh2-F8a), на 1440 — окном 480 посередине (S3-W4).
 * Esc, фон и крестик закрывают. Окно — Radix Dialog, как прежние листы (`ui/sheet.tsx`): фокус не уходит за окно,
 * фон инертен, после закрытия фокус возвращается (ревью Codex #235).
 */
export default function FormSheet({ label, onClose, children }: {
    /** Имя окна для экранного диктора: «Записать», «Записать к брони «Аренда»». */
    label: string;
    onClose: () => void;
    children: React.ReactNode;
}) {
    return (
        <Dialog.Root open onOpenChange={(open) => { if (!open) onClose(); }}>
            <Dialog.Portal>
                <Dialog.Overlay className="fixed inset-0 z-50 bg-sf-scrim" />
                <Dialog.Content aria-describedby={undefined}
                    className="fixed inset-x-0 bottom-0 z-50 max-h-[92dvh] overflow-y-auto rounded-t-sf-sheet bg-sf-page px-5 pb-[18px] pt-2.5 font-sf text-sf-ink outline-none lg:inset-x-auto lg:bottom-auto lg:left-1/2 lg:top-[90px] lg:max-h-[calc(100dvh-120px)] lg:w-[480px] lg:-translate-x-1/2 lg:rounded-sf-sheet lg:px-6 lg:pb-[22px] lg:pt-[46px] lg:shadow-[0_20px_60px_rgba(32,30,24,.3)]">
                    <Dialog.Title className="sr-only">{label}</Dialog.Title>
                    <div aria-hidden="true" className="mb-3 flex h-1 justify-center lg:hidden">
                        <span className="h-1 w-[38px] rounded-sm bg-sf-rule" />
                    </div>
                    <div className="flex flex-col gap-3">{children}</div>
                    {/* Крестик — последним: фокус при открытии встаёт в форму (сумму), а не на него. */}
                    <Dialog.Close asChild>
                        <IconButton aria-label="Закрыть" className="absolute right-2 top-1 lg:right-2.5 lg:top-2.5">
                            <X size={20} strokeWidth={1.8} />
                        </IconButton>
                    </Dialog.Close>
                </Dialog.Content>
            </Dialog.Portal>
        </Dialog.Root>
    );
}
