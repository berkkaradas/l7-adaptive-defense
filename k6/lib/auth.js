import http from 'k6/http';
import encoding from 'k6/encoding';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * JWT payload'ından memberId'yi okur.
 *
 * Neden gerekli: /auth/register 200 dönüyor ama gövdesi boş, /auth/login ise
 * yalnızca token dönüyor. memberId hiçbir yanıtta açık değil — ama sistemin bu
 * kullanıcıyı gördüğü isim "AUTH:<memberId>" ve etiketleme için o sayı şart.
 *
 * İmza DOĞRULANMIYOR, sadece okunuyor. Token'ın geçerliliğini Gateway kontrol
 * ediyor; k6 burada sadece bir istemci.
 */
export function decodeMemberId(token) {
    // JWT üç parça: header.payload.signature — ortadaki bizim istediğimiz.
    const payload = token.split('.')[1];
    // 'rawurl' = base64url, padding'siz. JWT tam olarak bunu kullanıyor;
    // düz 'std' ile çözmeye kalkarsan '-' ve '_' karakterlerinde patlar.
    const json = encoding.b64decode(payload, 'rawurl', 's');
    return JSON.parse(json).memberId;
}

/**
 * Bir kullanıcıyı kaydeder, giriş yaptırır, koşu boyunca lazım olan her şeyi
 * tek nesnede döndürür.
 *
 * Hata durumunda throw ediyor, check() kullanmıyor. setup() içinde bir şey
 * ters giderse koşu HİÇ başlamamalı: yarım kurulmuş bir koşudan çıkan veri,
 * yanlış olduğu belli olmayan veridir ve en tehlikeli olan da odur.
 */
export function registerAndLogin(baseUrl, username, password) {
    const body = JSON.stringify({ username, password });

    const registered = http.post(`${baseUrl}/auth/register`, body, { headers: JSON_HEADERS });
    if (registered.status !== 200) {
        throw new Error(`register failed for ${username}: ${registered.status} ${registered.body}`);
    }

    const loggedIn = http.post(`${baseUrl}/auth/login`, body, { headers: JSON_HEADERS });
    if (loggedIn.status !== 200) {
        throw new Error(`login failed for ${username}: ${loggedIn.status} ${loggedIn.body}`);
    }

    const token = loggedIn.json('token');
    const memberId = decodeMemberId(token);

    return {
        username,
        token,
        memberId,
        identity: `AUTH:${memberId}`,   // sistemin bu kullanıcıyı gördüğü isim
    };
}

/**
 * Bir rol için N kullanıcı üretir.
 *
 * Kullanıcı adı deseni: legit_3_r1756134022417
 * Koşu kimliği sonda, çünkü kayıtlar veritabanında birikiyor — ikinci koşuda
 * aynı isimle kayıt hata verir ve setup çöker. Ayrıca sonradan veritabanına
 * bakıp hangi kullanıcının hangi koşuya ait olduğunu görebiliyorsun.
 */
export function createUsers(baseUrl, role, count, runId, password) {
    const users = [];
    for (let i = 1; i <= count; i++) {
        users.push(registerAndLogin(baseUrl, `${role}_${i}_${runId}`, password));
    }
    return users;
}