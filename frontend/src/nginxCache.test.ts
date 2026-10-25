/**
 * Сторожа nginx: конфиг тестом не выполнить, поэтому сторож читает исходник. nginx фронта — прокси на бэк и в Keycloak,
 * закрытое снаружи, заголовки кэша (ANO-197, ANO-222, ANO-242); дверь у друга и compose сервера (ANO-242).
 * Без заголовка кэша браузер держит старый index.html, и после выкладки старая страница говорит с новым бэком.
 *
 * Спеки: docs/superpowers/specs/2026-10-17-index-cache-open-tab-design.md, 2026-10-25-nginx-door-design.md
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const conf = read('../nginx.conf');
// Предел тела и тайм-ауты — одни на обоих звеньях: nginx экранов и дверь у друга (ANO-242).
const LIMITS = ['client_max_body_size 1m;', 'proxy_connect_timeout 5s;', 'proxy_read_timeout 60s;', 'proxy_send_timeout 60s;'];
const block = (location: string) => conf.match(new RegExp(`location ${location} \\{([^}]*)\\}`))?.[1] ?? '';

describe('nginx фронта: прокси на бэк (ANO-197)', () => {
    it('схема запроса — от HTTPS-прокси впереди, а без него своя: иначе Spring строит адрес возврата http://', () => {
        expect(conf).toMatch(/map \$http_x_forwarded_proto \$forwarded_proto \{[^}]*default \$http_x_forwarded_proto;[^}]*"" \$scheme;[^}]*\}/);
        expect(conf).toMatch(/proxy_set_header X-Forwarded-Proto \$forwarded_proto;/);
    });

    it('публичный порт — из Host: на нестандартном порту адрес возврата не теряет порт (ревью Codex на #171)', () => {
        expect(conf).toMatch(/map \$http_host \$forwarded_port \{[^}]*~:\(\?<port>\\d\+\)\$ \$port;[^}]*\}/);
        expect(conf).toMatch(/proxy_set_header X-Forwarded-Port \$forwarded_port;/);
    });

    it('бэк ищется по имени на каждый запрос: фронт стартует и без бэка (находка 15.10)', () => {
        expect(conf).toMatch(/resolver 127\.0\.0\.11/);
        expect(conf).toMatch(/set \$backend http:\/\/backend:8080;/);
        expect(conf).toMatch(/proxy_pass \$backend;/);
    });
});

describe('nginx фронта: кэш (ANO-222)', () => {
    it('index.html — без хранения: браузер сверяет ETag и после выкладки грузит новый бандл', () => {
        expect(block('= /index\\.html')).toMatch(/add_header\s+Cache-Control\s+"no-cache"/);
    });

    it('бандлы с хэшем в имени — навсегда, а чего нет — 404, а не страница', () => {
        const assets = block('/assets/');
        expect(assets).toMatch(/add_header\s+Cache-Control\s+"public, max-age=31536000, immutable"/);
        expect(assets).toMatch(/try_files \$uri =404;/);
    });
});

describe('nginx фронта: Keycloak под /auth (ANO-242)', () => {
    it('закрытое снаружи — 404, и с ^~: регулярка, добавленная потом, закрытое не перехватит', () => {
        expect(conf).toMatch(/location \^~ \/auth\/admin\s*\{\s*return 404;\s*\}/);
        expect(conf).toMatch(/location \^~ \/auth\/realms\/master\s*\{\s*return 404;\s*\}/);
        expect(conf).toMatch(/location \^~ \/auth\/realms\/selfin\/account\s*\{\s*return 404;\s*\}/);
        // Корень Keycloak наружу не выставлять — совет самого Keycloak.
        expect(conf).toMatch(/location = \/auth\/\s*\{\s*return 404;\s*\}/);
    });

    it('/auth/ — в Keycloak по имени на каждый запрос, адрес как есть, заголовки — как у приложения', () => {
        const auth = block('/auth/');
        expect(auth).toContain('set $keycloak http://keycloak:8080;');
        expect(auth).toContain('proxy_pass $keycloak;');
        for (const header of ['Host $http_host', 'X-Forwarded-For $proxy_add_x_forwarded_for',
            'X-Forwarded-Proto $forwarded_proto', 'X-Forwarded-Port $forwarded_port']) {
            expect(auth).toContain(`proxy_set_header ${header};`);
        }
    });

    it('предел тела и тайм-ауты — явные: в цепочке прокси действует самый маленький и самый короткий', () => {
        for (const line of LIMITS) {
            expect(conf).toContain(line);
        }
    });
});

describe('дверь у друга (ANO-242): deploy/nginx', () => {
    const door = () => read('../../deploy/nginx/selfin.conf.template');
    const acme = () => read('../../deploy/nginx/selfin-acme.conf.template');

    it('порт 80 в обоих шагах: проверка Let\'s Encrypt из своей папки, остальное — на https', () => {
        for (const file of [door(), acme()]) {
            expect(file).toMatch(/location \^~ \/\.well-known\/acme-challenge\/\s*\{\s*root \/var\/www\/selfin-acme;\s*\}/);
            expect(file).toContain('return 301 https://$host$request_uri;');
        }
        expect(acme()).not.toContain('listen 443');
    });

    it('порт 443: сертификат по имени, внутрь — только на свой порт сервера', () => {
        expect(door()).toContain('listen 443 ssl;');
        expect(door()).toMatch(/ssl_certificate +\/etc\/letsencrypt\/live\/\$\{SELFIN_SERVER_NAME\}\/fullchain\.pem;/);
        expect(door()).toMatch(/ssl_certificate_key +\/etc\/letsencrypt\/live\/\$\{SELFIN_SERVER_NAME\}\/privkey\.pem;/);
        expect(door()).toContain('proxy_pass http://127.0.0.1:${SELFIN_HTTP_PORT};');
    });

    it('первое звено: X-Forwarded-For перезаписан адресом клиента, схема — своя, Host — с портом', () => {
        expect(door()).toContain('proxy_set_header X-Forwarded-For $remote_addr;');
        expect(door()).not.toContain('$proxy_add_x_forwarded_for');
        expect(door()).toContain('proxy_set_header X-Forwarded-Proto $scheme;');
        expect(door()).toContain('proxy_set_header Host $http_host;');
    });

    it('предел тела и тайм-ауты — те же, что у nginx экранов', () => {
        for (const line of LIMITS) {
            expect(door()).toContain(line);
        }
    });

    it('шаблоны меняют только имя и порт: прочие $ — переменные nginx, envsubst их не трогает', () => {
        const names = [...door().matchAll(/\$\{([A-Z_]+)\}/g), ...acme().matchAll(/\$\{([A-Z_]+)\}/g)].map((m) => m[1]);
        expect(new Set(names)).toEqual(new Set(['SELFIN_SERVER_NAME', 'SELFIN_HTTP_PORT']));
    });
});

describe('compose сервера (ANO-242): без Caddy', () => {
    it('Caddy нет, nginx экранов слушает только на самом сервере: снаружи — только через дверь', () => {
        const compose = read('../../deploy/docker-compose.yml');
        expect(compose).not.toMatch(/caddy/i);
        expect(compose).toContain('- "127.0.0.1:${SELFIN_HTTP_PORT:-8280}:80"');
    });
});
