# Saldiri fazi boyunca orders-service'i dusurur, sonra geri kaldirir.
# k6 ile AYNI ANDA, ayri bir terminalde baslatilir.
#
# Duman testi:  .\scripts\inject_outage.ps1 -AttackStart 20 -AttackDuration 20
# Gercek kosu:  .\scripts\inject_outage.ps1
param(
    [int]$AttackStart = 120,
    [int]$AttackDuration = 180
)

Write-Host "[outage] saldiri fazina $AttackStart sn kaldi..." -ForegroundColor Cyan
Start-Sleep -Seconds $AttackStart

# 'stop' degil 'kill': stop varsayilan olarak 10 saniye nazik kapanma bekliyor,
# bu da 20 saniyelik duman testinde zamanlamayi tamamen kaydirirdi. kill aninda
# dusuruyor -- ve gercek bir arizaya da daha yakin.
Write-Host "[outage] orders-service dusuruluyor" -ForegroundColor Red
docker compose kill orders-service

Start-Sleep -Seconds $AttackDuration

Write-Host "[outage] orders-service geri kaldiriliyor" -ForegroundColor Green
docker compose start orders-service

Write-Host "[outage] bitti" -ForegroundColor Cyan