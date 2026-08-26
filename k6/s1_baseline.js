import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { createUsers } from './lib/auth.js';
import {
    GATEWAY_URL, RUN_ID, PASSWORD, PHASES,
    LEGIT_USERS, LEGIT_RATE_PER_USER,
} from './lib/config.js';

/**
 * S1 — Normal Baseline (Karar Kaydı 11.1).
 *
 * Saldırı YOK. Bu senaryonun işi her metrik için referans değer üretmek:
 * "saldırı sırasında P95 şu kadardı" cümlesi ancak saldırısız hali bilinirse
 * anlam taşıyor.
 *
 * Fazlar saldırgan olmasa da aynen korunuyor — 'attack' fazı burada sadece
 * meşru trafiğin devamı. Süre ve şekil birebir aynı olmazsa S1 ile S2-S4
 * karşılaştırılamaz.
 */

const LEGIT_TOTAL_RATE = LEGIT_USERS * LEGIT_RATE_PER_USER;

function legitPhase(name) {
    return {
        // AÇIK MODEL. constant-vus olsaydı sanal kullanıcılar cevabı bekler,
        // sistem yavaşlayınca yük de düşerdi — yük üreteci sistemle işbirliği
        // yapar ve bozulmayı gizlerdi. Saldırgan işbirliği yapmaz. Sabit varış
        // hızı, sistem ne yaparsa yapsın baskıyı koruyor (11.7).
        executor: 'constant-arrival-rate',
        exec: 'legitTraffic',
        startTime: PHASES[name].start,
        duration: PHASES[name].duration,
        rate: LEGIT_TOTAL_RATE,
        timeUnit: '1s',
        preAllocatedVUs: LEGIT_USERS * 4,
        // Sistem yavaşlarsa k6'nın hızı koruyabilmesi için yedek VU. Yetmezse
        // k6 'dropped_iterations' sayar — o sayı sıfır değilse yük üreteci
        // yetişememiş demektir ve o koşu geçersizdir.
        maxVUs: LEGIT_USERS * 20,
    };
}

// Her faz ayrı bir senaryo. k6 her metriği ürettiği senaryonun adıyla
// etiketliyor, dolayısıyla analiz zaman damgasından faz hesaplamıyor —
// sadece etikete bakıyor (11.7).
export const options = {
    scenarios: {
        warmup:   legitPhase('warmup'),
        baseline: legitPhase('baseline'),
        attack:   legitPhase('attack'),
        recovery: legitPhase('recovery'),
    },
};

export function setup() {
    const legit = createUsers(GATEWAY_URL, 'legit', LEGIT_USERS, RUN_ID, PASSWORD);
    console.log(`RUN_ID=${RUN_ID} legit=[${legit.map((u) => u.identity).join(', ')}]`);
    return { legit };
}

export function legitTraffic(data) {
    const n = exec.scenario.iterationInTest;

    // Kullanıcılar sırayla dönüyor: her kimlik eşit yük alıyor. Rastgele
    // seçseydik koşular arası dağılım değişir, tekrarlar karşılaştırılamazdı.
    const user = data.legit[n % data.legit.length];

    // Etiketler analizin can damarı: 'role' FPR hesabı için, 'identity' ise
    // l7.decisions kayıtlarıyla join için.
    const tags = { role: 'legit', identity: user.identity };

    // İki endpoint dönüşümlü — gerçek kullanıcı tek bir uca yapışmaz.
    const url = (n % 2 === 0)
        ? `${GATEWAY_URL}/orders?memberId=${user.memberId}`
        : `${GATEWAY_URL}/items`;

    const response = http.get(url, {
        headers: { Authorization: `Bearer ${user.token}` },
        tags,
    });

    check(response, { 'legitimate request served': (r) => r.status === 200 }, tags);
}