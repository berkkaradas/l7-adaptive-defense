import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { createUsers } from './lib/auth.js';
import {
    GATEWAY_URL, RUN_ID, PASSWORD, PHASES,
    LEGIT_USERS, LEGIT_RATE_PER_USER, INNOCENT_USERS,
    CLIENT_IP, ATTACK, INNOCENT_LOGIN,
} from './lib/config.js';

/**
 * S3 — Mixed NAT Traffic (Karar Kaydı 11.1). RQ2'yi hedefliyor.
 *
 * Dört aktör, hepsi aynı IP'nin arkasında:
 *
 *   legit (5)     kimliği doğrulanmış, geziniyor        AUTH:<id>
 *   innocent (2)  KENDİ hesabına giriş deniyor          ATTEMPT:<ip>,innocent_N
 *   victim (1)    saldırıya uğrayan hesabın sahibi      ATTEMPT:<ip>,victim
 *   attacker (1)  kurbanın hesabına şifre deniyor       ATTEMPT:<ip>,victim
 *
 * ÖLÇÜLEN ASIL ŞEY: innocent kullanıcılar etkileniyor mu? Aynı IP'yi bir
 * saldırganla paylaşıyorlar ama kullanıcı adları farklı, dolayısıyla Mode B
 * kimlikleri de farklı (2.4). Tasarım çalışıyorsa CDR sıfır çıkmalı.
 *
 * victim ise tasarımın sınırı: saldırganla AYNI kimliği paylaşıyor, çünkü
 * ikisi de aynı IP'den aynı hesaba giriş deniyor. Kilitleneceğini biliyoruz;
 * ne kadar kilitlendiğini ölçüyoruz. Kimlik bazlı metriklerin dışında tutuluyor
 * çünkü ona verilen ceza YANLIŞ DEĞİL — o kimliğe gerçekten saldırı var.
 */

const LEGIT_TOTAL_RATE = LEGIT_USERS * LEGIT_RATE_PER_USER;

function legitPhase(name) {
    return {
        executor: 'constant-arrival-rate',
        exec: 'legitTraffic',
        startTime: PHASES[name].start,
        duration: PHASES[name].duration,
        rate: LEGIT_TOTAL_RATE,
        timeUnit: '1s',
        preAllocatedVUs: LEGIT_USERS * 4,
        maxVUs: LEGIT_USERS * 20,
    };
}

function loginPhase(execName, name) {
    return {
        executor: 'constant-arrival-rate',
        exec: execName,
        startTime: PHASES[name].start,
        duration: PHASES[name].duration,
        rate: INNOCENT_LOGIN.rate,
        timeUnit: INNOCENT_LOGIN.timeUnit,
        preAllocatedVUs: 2,
        maxVUs: 5,
        env: { PHASE: name },
    };
}

export const options = {
    scenarios: {
        warmup:   legitPhase('warmup'),
        baseline: legitPhase('baseline'),
        attack:   legitPhase('attack'),
        recovery: legitPhase('recovery'),

        bruteforce: {
            executor: 'constant-arrival-rate',
            exec: 'attackTraffic',
            startTime: PHASES.attack.start,
            duration: PHASES.attack.duration,
            rate: ATTACK.rate,
            timeUnit: ATTACK.timeUnit,
            preAllocatedVUs: 2,
            maxVUs: 10,
        },

        // Masumlar: baseline'da kontrol, attack'ta ölçüm, recovery'de toparlanma.
        innocent_baseline: loginPhase('innocentLogin', 'baseline'),
        innocent_attack:   loginPhase('innocentLogin', 'attack'),
        innocent_recovery: loginPhase('innocentLogin', 'recovery'),

        victim_baseline: loginPhase('victimLogin', 'baseline'),
        victim_attack:   loginPhase('victimLogin', 'attack'),
        victim_recovery: loginPhase('victimLogin', 'recovery'),
    },
};

export function setup() {
    const legit = createUsers(GATEWAY_URL, 'legit', LEGIT_USERS, RUN_ID, PASSWORD);
    const innocent = createUsers(GATEWAY_URL, 'innocent', INNOCENT_USERS, RUN_ID, PASSWORD);
    const victimUser = createUsers(GATEWAY_URL, 'victim', 1, RUN_ID, PASSWORD)[0];

    const victim = victimUser.username;
    const attackedIdentity = `ATTEMPT:${CLIENT_IP},${victim.toLowerCase()}`;

    console.log(`RUN_ID=${RUN_ID}`);
    console.log(`legit=[${legit.map((u) => u.identity).join(', ')}]`);
    console.log(`innocent login identities=[${innocent
        .map((u) => `ATTEMPT:${CLIENT_IP},${u.username.toLowerCase()}`).join(', ')}]`);
    console.log(`attacked identity=${attackedIdentity}  <- saldirgan ve kurban burada bulusuyor`);

    return { legit, innocent, victim, attackedIdentity };
}

export function legitTraffic(data) {
    const n = exec.scenario.iterationInTest;
    const user = data.legit[n % data.legit.length];
    const tags = { role: 'legit', identity: user.identity, phase: exec.scenario.name };

    const url = (n % 2 === 0)
        ? `${GATEWAY_URL}/orders?memberId=${user.memberId}`
        : `${GATEWAY_URL}/items`;

    const response = http.get(url, {
        headers: { Authorization: `Bearer ${user.token}` },
        tags,
    });

    check(response, { 'legitimate request served': (r) => r.status === 200 }, tags);
}

export function attackTraffic(data) {
    const n = exec.scenario.iterationInTest;
    const tags = { role: 'attacker', identity: data.attackedIdentity, phase: 'attack' };

    const body = JSON.stringify({ username: data.victim, password: `wrong-${n}` });

    const response = http.post(`${GATEWAY_URL}/auth/login`, body, {
        headers: { 'Content-Type': 'application/json' },
        tags,
    });

    check(response, { 'attack did not succeed': (r) => r.status !== 200 }, tags);
}

/** Aynı NAT'ın arkasında, kendi hesabına doğru şifreyle giriş yapan meşru kullanıcı. */
export function innocentLogin(data) {
    const n = exec.scenario.iterationInTest;
    const user = data.innocent[n % data.innocent.length];

    // Kendi kullanıcı adı → kendi Mode B kimliği → saldırganla paylaşmıyor.
    const identity = `ATTEMPT:${CLIENT_IP},${user.username.toLowerCase()}`;
    const tags = { role: 'innocent', identity, phase: __ENV.PHASE };

    const body = JSON.stringify({ username: user.username, password: PASSWORD });

    const response = http.post(`${GATEWAY_URL}/auth/login`, body, {
        headers: { 'Content-Type': 'application/json' },
        tags,
    });

    check(response, { 'innocent could log in': (r) => r.status === 200 }, tags);
}

/** Saldırıya uğrayan hesabın sahibi. Saldırganla aynı kimliği paylaşıyor. */
export function victimLogin(data) {
    const tags = { role: 'victim', identity: data.attackedIdentity, phase: __ENV.PHASE };

    const body = JSON.stringify({ username: data.victim, password: PASSWORD });

    const response = http.post(`${GATEWAY_URL}/auth/login`, body, {
        headers: { 'Content-Type': 'application/json' },
        tags,
    });

    check(response, { 'victim could log in': (r) => r.status === 200 }, tags);
}