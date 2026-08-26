import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { createUsers } from './lib/auth.js';
import {
    GATEWAY_URL, RUN_ID, PASSWORD, PHASES,
    LEGIT_USERS, LEGIT_RATE_PER_USER, CLIENT_IP, ATTACK,
} from './lib/config.js';

/**
 * S2 — Low-and-Slow Brute Force (Karar Kaydı 11.1). RQ1'i hedefliyor.
 *
 * Saldırgan tek bir hesaba, statik eşiğin ALTINDA bir hızla şifre deniyor.
 * Bu senaryo tezin varlık sebebi: baseline koşulda saldırgan hiç yakalanmıyor
 * çünkü hız limiti hiç tetiklenmiyor. Yakalanabilmesi için hıza değil
 * DAVRANIŞA bakan bir şey gerekiyor.
 *
 * Meşru trafik S1'deki ile birebir aynı — tek fark saldırı fazında devreye
 * giren beşinci senaryo. Meşru tarafı değiştirseydik S1 referansı işe yaramazdı.
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

export const options = {
    scenarios: {
        warmup:   legitPhase('warmup'),
        baseline: legitPhase('baseline'),
        attack:   legitPhase('attack'),
        recovery: legitPhase('recovery'),

        // Saldırgan yalnızca saldırı fazında. Ayrı bir senaryo olması iki işe
        // yarıyor: k6 onu ayrı etiketliyor, ve meşru trafiğin hızını hiç
        // etkilemiyor — iki yük birbirinden tamamen bağımsız akıyor.
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
    },
};

export function setup() {
    const legit = createUsers(GATEWAY_URL, 'legit', LEGIT_USERS, RUN_ID, PASSWORD);

    // Saldırgan gerçek bir hesabı hedefliyor — brute force böyle çalışır,
    // kullanıcı adı bilinir, şifre aranır.
    const victim = legit[0].username;

    // Gateway kullanıcı adını küçük harfe çeviriyor (2.5), o yüzden burada da
    // çeviriyoruz. Aksi halde etiket ile karar kaydındaki kimlik uyuşmaz ve
    // join sessizce boş döner.
    const attackerIdentity = `ATTEMPT:${CLIENT_IP},${victim.toLowerCase()}`;

    console.log(`RUN_ID=${RUN_ID}`);
    console.log(`legit=[${legit.map((u) => u.identity).join(', ')}]`);
    console.log(`attacker=${attackerIdentity} (victim=${victim})`);

    return { legit, victim, attackerIdentity };
}

export function legitTraffic(data) {
    const n = exec.scenario.iterationInTest;
    const user = data.legit[n % data.legit.length];
    const tags = { role: 'legit', identity: user.identity };

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
    const tags = { role: 'attacker', identity: data.attackerIdentity };

    // Her denemede farklı bir yanlış şifre — gerçek brute force sözlük
    // gezer, aynı şifreyi tekrarlamaz.
    const body = JSON.stringify({
        username: data.victim,
        password: `wrong-${n}`,
    });

    const response = http.post(`${GATEWAY_URL}/auth/login`, body, {
        headers: { 'Content-Type': 'application/json' },
        tags,
    });

    // Beklenen cevaplar koşula göre değişiyor ve İKİSİ DE normal:
    //   baseline → hep 401 (auth-service reddediyor, savunma devrede değil)
    //   adaptive → önce 401, sonra 429 (RATE_LIMIT), sonra gecikmeli 401 (TARPIT)
    // Tek kabul edilemez sonuç 200: o, saldırganın şifreyi bulması demek.
    check(response, { 'attack did not succeed': (r) => r.status !== 200 }, tags);
}