import { createCheckpoint, createPlanWithFact, deleteEvent, updateCheckpoint } from '../api';
import { ApiError } from '../api/client';
import { AttemptKeys } from './attemptKey';
import type { BalanceCheckpointCreateDto, FinancialEventCreateDto } from '../types/api';

type Api = {
    createCheckpoint: typeof createCheckpoint;
    updateCheckpoint: typeof updateCheckpoint;
    createPlanWithFact: typeof createPlanWithFact;
    deleteEvent: typeof deleteEvent;
};

/**
 * Записи первого входа (план среза 2, задача 2.2): помнит, что записал каждый ответ, — «Назад» и новый ответ
 * правят записанное, а не пишут второй раз. Тот же ответ — без записи: повтор «Дальше» ничего не пишет.
 *
 * Строка плана правится заменой: прежняя удаляется — у повтора вся серия, — новая пишется. Правка серии на месте
 * не годится: дата начала у повтора неизменна (I8), а новое число месяца снимает строку, по которой правят, — сервер
 * отвечает 500. В журнале остаётся одна строка.
 *
 * Ключ попытки — свой у каждого ответа (`AttemptKeys` на ответ): ответ потерялся — повтор приходит с тем же ключом,
 * и сервер отдаёт уже записанное. Общая память ключей отдала бы попытку одной строки платежа другой.
 * Записи идут по одной: удаление и запись одного ответа не перемешиваются с соседними.
 */
export class FirstEntryWrites {
    private readonly saved = new Map<string, { id: string; json: string; series: boolean }>();
    private readonly keys = new Map<string, AttemptKeys>();
    private queue: Promise<unknown> = Promise.resolve();

    constructor(private readonly api: Api = { createCheckpoint, updateCheckpoint, createPlanWithFact, deleteEvent }) {}

    private run<T>(write: () => Promise<T>): Promise<T> {
        const next = this.queue.then(write);
        this.queue = next.catch(() => undefined);
        return next;
    }

    /**
     * Вопрос 1 — сверка остатка; другой ответ — правка той же сверки. `true` — что-то записано.
     * ponytail: у сверки на сервере ключа попытки нет — потерянный ответ даст вторую сверку тем же днём и суммой;
     * число от неё не меняется. Ключ — если сверку начнут писать и задним числом из первого входа.
     */
    balance(dto: BalanceCheckpointCreateDto): Promise<boolean> {
        return this.run(async () => {
            const json = JSON.stringify(dto);
            const prev = this.saved.get('balance');
            if (prev?.json === json) return false;
            const cp = prev ? await this.api.updateCheckpoint(prev.id, dto) : await this.api.createCheckpoint(dto);
            this.saved.set('balance', { id: cp.id, json, series: false });
            return true;
        });
    }

    /** Ответ `answer` — строка плана: доход, строка платежа. `null` — ответа нет: записанная строка удаляется. */
    plan(answer: string, dto: FinancialEventCreateDto | null): Promise<boolean> {
        return this.run(async () => {
            const json = JSON.stringify(dto);
            const prev = this.saved.get(answer);
            if ((prev?.json ?? 'null') === json) return false;
            if (prev) {
                // Удаление прошло, а ответ потерялся: повтор получит 404 — строки уже нет, этого и хотели.
                await this.api.deleteEvent(prev.id, prev.series ? 'ALL' : 'THIS')
                    .catch(err => { if (!(err instanceof ApiError && err.status === 404)) throw err; });
                this.saved.delete(answer);
            }
            if (dto) {
                const keys = this.keys.get(answer) ?? new AttemptKeys();
                this.keys.set(answer, keys);
                const ev = await this.api.createPlanWithFact(keys, dto);
                this.saved.set(answer, { id: ev.id, json, series: dto.recurring != null });
            }
            return true;
        });
    }
}
