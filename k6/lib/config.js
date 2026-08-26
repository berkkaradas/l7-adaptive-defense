/**
 * Koşuya ait tüm ayarlar tek yerde. Hiçbiri senaryo dosyalarının içine
 * gömülmüyor: senaryolar arasında değişmemesi gereken şeylerin gerçekten
 * değişmediğini ancak böyle garanti edebiliyoruz.
 */

// Ölçülen sistem buradan giriliyor. Setup da buradan geçiyor — başarılı
// kayıtlar 200 dönüyor, 200 IGNORED sayılıyor, dolayısıyla fixture trafiği
// hiçbir skor veya karar üretmiyor (Karar Kaydı 11.6).
export const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

// Her koşuya benzersiz kimlik: kullanıcı adı çakışmasını önlüyor ve artefakt
// klasörünü isimlendiriyor. Elle numara vermek insan hatasına açık — aynı
// numarayı iki kez kullanırsan bir koşunun verisini üzerine yazarsın.
export const RUN_ID = __ENV.RUN_ID || `r${Date.now()}`;

export const PASSWORD = 'CorrectHorseBattery1';

// SMOKE=1 ile hattı 50 saniyede test edersin. Varsayılanlar GERÇEK deney
// değerleri, yani yanlışlıkla kısa koşup onu gerçek veri sanma riski yok.
const SMOKE = __ENV.SMOKE === '1';

// Faz süreleri. Gerekçeler 11.7'de:
//   warmup   → soğuk JVM verisi atılıyor (ilk istek 650 ms, ısınınca 8-16 ms)
//   baseline → saldırısız referans; "saldırıda P95 40 ms" ancak buna göre anlamlı
//   attack   → 180 sn, çünkü pencere 180 sn; daha kısası tam bir pencere görmez
//   recovery → ceza bırakılıyor mu; 15.1'deki salınım burada görünür
export const PHASES = SMOKE
    ? {
        warmup:   { start: '0s',  duration: '10s' },
        baseline: { start: '10s', duration: '10s' },
        attack:   { start: '20s', duration: '20s' },
        recovery: { start: '40s', duration: '10s' },
    }
    : {
        warmup:   { start: '0s',   duration: '60s'  },
        baseline: { start: '60s',  duration: '60s'  },
        attack:   { start: '120s', duration: '180s' },
        recovery: { start: '300s', duration: '60s'  },
    };

// Meşru kullanıcı sayısı ve kullanıcı başına hız.
// Bucket tavanı kimlik başına 2 istek/sn; 1 istek/sn tam yarısı, yani meşru
// kullanıcı hiçbir koşulda tabana takılmıyor. Takılsaydı FPR, Risk Engine'i
// değil bucket kalibrasyonunu ölçerdi.
export const LEGIT_USERS = Number(__ENV.LEGIT_USERS || 5);
export const LEGIT_RATE_PER_USER = Number(__ENV.LEGIT_RATE || 1);

// Gateway'in bizi hangi IP'den geldiğimizi gördüğü adres.
//
// Neden gerekli: saldırganın kimliği Mode B, yani "ATTEMPT:<ip>,<username>"
// (Karar Kaydı 2.4). Etiketleme için bu stringi kurmamız gerekiyor ama k6
// kendi kaynak IP'sini Gateway'in gördüğü haliyle bilemez — Docker ağ geçidi
// arada duruyor.
//
// KIRILGAN NOKTA: yanlışsa TTD "yakalanmadı" çıkar ve bu sessiz bir hatadır.
// Analiz script'i buna karşı uyarı basıyor. Docker ağı değişirse Gateway
// logundaki "identity=ATTEMPT:..." satırından doğru değeri al.
export const CLIENT_IP = __ENV.CLIENT_IP || '172.19.0.1';

// Saldırgan hızı: 2 saniyede 1 istek = 0.5 istek/sn.
// Bucket tavanı kimlik başına 2 istek/sn olduğu için statik limiter bunu
// asla tetiklemiyor. S2'nin bütün anlamı bu sayıda.
export const ATTACK = {
    rate: Number(__ENV.ATTACK_RATE || 1),
    timeUnit: __ENV.ATTACK_UNIT || '2s',
};