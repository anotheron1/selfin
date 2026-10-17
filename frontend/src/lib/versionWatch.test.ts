import { describe, expect, it } from 'vitest';
import { versionWatch } from './versionWatch';

/** Сервер, отвечающий по очереди; последний ответ повторяется. */
const server = (...answers: (string | null | Error)[]) => {
    let i = 0;
    return async () => {
        const answer = answers[Math.min(i++, answers.length - 1)];
        if (answer instanceof Error) throw answer;
        return answer;
    };
};

describe('versionWatch — открытая вкладка после выкладки (ANO-222)', () => {
    it('первый вызов запоминает коммит сервера; тот же коммит — не обновление', async () => {
        const changed = versionWatch(server('aaa', 'aaa'));
        expect(await changed()).toBe(false);
        expect(await changed()).toBe(false);
    });

    it('коммит сервера сменился с открытия вкладки — обновление', async () => {
        const changed = versionWatch(server('aaa', 'bbb'));
        await changed();
        expect(await changed()).toBe(true);
    });

    it('сборка без GIT_COMMIT и пустой ответ — не повод звать обновляться', async () => {
        const unknown = versionWatch(server('unknown', 'unknown'));
        expect(await unknown()).toBe(false);
        expect(await unknown()).toBe(false);
        // unknown не запоминается и с настоящим коммитом не сравнивается — ни в одну, ни в другую сторону
        const becameUnknown = versionWatch(server('aaa', 'unknown'));
        await becameUnknown();
        expect(await becameUnknown()).toBe(false);
        const wasUnknown = versionWatch(server('unknown', 'bbb', 'bbb'));
        expect(await wasUnknown()).toBe(false);
        expect(await wasUnknown()).toBe(false);
        expect(await wasUnknown()).toBe(false);
        const empty = versionWatch(server('aaa', null));
        await empty();
        expect(await empty()).toBe(false);
    });

    it('при открытии сверить не удалось — первая удачная проверка предлагает обновиться (ревью Codex на #169)', async () => {
        // Вкладка открылась, пока сервер перезапускался: её код мог остаться старым, а новый коммит молча стал бы базой.
        const failed = versionWatch(server(new Error('нет связи'), 'bbb'));
        expect(await failed()).toBe(false);
        expect(await failed()).toBe(true);
        const noAnswer = versionWatch(server(null, 'bbb'));
        expect(await noAnswer()).toBe(false);
        expect(await noAnswer()).toBe(true);
    });

    it('проверки идут по очереди: ранний ответ второй не становится базой (ревью Codex на #169)', async () => {
        // Первый запрос ещё в пути, вкладку скрыли и вернули через выкладку — второй ответил бы раньше и новым коммитом.
        let releaseFirst!: (commit: string) => void;
        const answers = [new Promise<string>((resolve) => (releaseFirst = resolve)), Promise.resolve('bbb')];
        let call = 0;
        const changed = versionWatch(() => answers[call++]);
        const opening = changed();
        const visible = changed();
        releaseFirst('aaa');
        expect(await opening).toBe(false);
        expect(await visible).toBe(true);
    });

    it('сетевая ошибка — не обновление и не сбивает запомненный коммит', async () => {
        const changed = versionWatch(server('aaa', new Error('нет связи'), 'bbb'));
        await changed();
        expect(await changed()).toBe(false);
        expect(await changed()).toBe(true);
    });
});
