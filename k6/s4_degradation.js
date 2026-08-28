import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { createUsers } from './lib/auth.js';
import {
    GATEWAY_URL, RUN_ID, PASSWORD, PHASES, LEGIT_USERS, DEGRADED,
} from './lib/config.js';

/**
 * S4 -- Internal Degradation (Karar Kaydı 11.1).
 *
 * Saldırı fazında orders-service durduruluyor, yani /orders ve /items
 * çağrıları 5xx dönüyor. Hem meşru kullanıcılar hem de bir "ağır kullanıcı"
 * aynı arızayla karşılaşıyor.
 *
 * ÖLÇÜLEN ŞEY: 4.7'deki üç durumlu atıf testi. Bir 5xx, kullanıcının
 * davranışını değil sistemin durumunu anlatır -- dolayısıyla her hatayı
 * birine kesmek, bir arızada o an sitede olan herkesi cezalandırmak olurdu.
 * Üçü de tek koşuda görünmeli:
 *
 *   ilk saniyeler  toplam hata < 20        -> Durum 1 -> saldırgan skorlanır
 *   sonrası        saldırgan payı ~%60     -> Durum 2 -> cezası sürer
 *   meşru kullanıcı payı ~%8               -> Durum 3 -> dokunulmaz
 *
 * Saldırganın kimliği AUTH:<memberId> -- token'dan geliyor, yani S2/S3'teki
 * CLIENT_IP kırılganlığı burada yok.
 */

function legitPhase(name) {
    return {
        executor: 'constant-arrival-rate',
        exec: 'legitTraffic',
        startTime: PHASES[name].start,
        duration: PHASES[name].duration,
        rate: DEGRADED.legitRate,
        timeUnit: DEGRADED.legitUnit,
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

        // Ağır kullanıcı yalnızca saldırı fazında -- yani servis düşükken.
        heavyuser: {
            executor: 'constant-arrival-rate',
            exec: 'attackTraffic',
            startTime: PHASES.attack.start,
            duration: PHASES.attack.duration,
            rate: DEGRADED.attackerRate,
            timeUnit: DEGRADED.attackerUnit,
            preAllocatedVUs: 4,
            maxVUs: 20,
        },
    },
};

export function setup() {
    const legit = createUsers(GATEWAY_URL, 'legit', LEGIT_USERS, RUN_ID, PASSWORD);
    const attacker = createUsers(GATEWAY_URL, 'heavy', 1, RUN_ID, PASSWORD)[0];

    console.log(`RUN_ID=${RUN_ID}`);
    console.log(`legit=[${legit.map((u) => u.identity).join(', ')}]`);
    console.log(`heavy user=${attacker.identity}  <- hatalarin cogunlugunu bu uretecek`);

    return { legit, attacker };
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

    // Saldırı fazında 5xx BEKLENEN sonuç -- servis kasıtlı olarak durduruldu.
    // O yüzden burada 200 aramıyoruz; sadece isteğin gerçekten gittiğini
    // doğruluyoruz. Beklenen hatayı "başarısızlık" saymak yanıltıcı olurdu.
    check(response, { 'request completed': (r) => r.status !== 0 }, tags);
}

export function attackTraffic(data) {
    const user = data.attacker;
    const tags = { role: 'attacker', identity: user.identity, phase: 'attack' };

    const response = http.get(`${GATEWAY_URL}/orders?memberId=${user.memberId}`, {
        headers: { Authorization: `Bearer ${user.token}` },
        tags,
    });

    check(response, { 'request completed': (r) => r.status !== 0 }, tags);
}