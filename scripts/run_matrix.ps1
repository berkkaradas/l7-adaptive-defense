<#
  Tam matris: 4 senaryo x 2 kosul x N tekrar.

  Kosullar DONUSUMLU cagriliyor (11.7): laptopun durumu saatler icinde
  degisiyor (termal throttle, arka plan isleri). Bir kosulun tum kosularini
  arka arkaya yaparsak o kayma "kosul farki" gibi gorunur.

  Ornek:
    .\scripts\run_matrix.ps1                        # 40 kosu, ~5-6 saat
    .\scripts\run_matrix.ps1 -Repeats 1 -Smoke      # 8 kisa kosu, hatti dene
    .\scripts\run_matrix.ps1 -Scenarios s2,s4       # sadece iki senaryo
#>
param(
    [string[]]$Scenarios = @('s1','s2','s3','s4'),
    [int]$Repeats = 5,
    [switch]$Smoke
)

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$runOnce = Join-Path $PSScriptRoot 'run_once.ps1'
$total = $Scenarios.Count * 2 * $Repeats
$done = 0
$started = Get-Date

Write-Host "MATRIS: $total kosu ($($Scenarios -join ', ')) x 2 kosul x $Repeats tekrar" -ForegroundColor Yellow
Write-Host "baslangic: $($started.ToString('HH:mm'))" -ForegroundColor Yellow

# Tekrar EN DIS dongu: her senaryonun tekrarlari oturuma yayiliyor,
# arka arkaya degil. Kosul ic dongude, yani baseline/adaptive surekli
# donusumlu gidiyor.
foreach ($rep in 1..$Repeats) {
    foreach ($sc in $Scenarios) {
        foreach ($cond in @('baseline','adaptive')) {
            $done++
            Write-Host ""
            Write-Host "=== [$done/$total] $sc $cond tekrar $rep ===" -ForegroundColor Yellow

            # Kosunun kendisi ne sekilde patlarsa patlasin matris devam etsin.
            # run_once icinde ErrorActionPreference='Continue' var, ama docker
            # daemon'inin olmesi gibi gercek bir terminating error 4+ saatlik
            # matrisi ortadan ikiye bolerdi. catch bilerek bos degil: sessizce
            # yutmak, sabah "bu kosu neden yok" sorusunu cevapsiz birakir.
            try {
                if ($Smoke) {
                    & $runOnce -Scenario $sc -Condition $cond -Run $rep -Smoke
                } else {
                    & $runOnce -Scenario $sc -Condition $cond -Run $rep
                }
            } catch {
                Write-Host "  !! kosu cakildi: $_" -ForegroundColor Red
            }

            # Kosu gecersiz olsa bile DURMUYORUZ -- matris aksin, gecersizler
            # sonda raporlansin, onlari tek tek tekrar kosarsin.
            Start-Sleep -Seconds 5
        }
    }
}

# ------------------------------------------------------------------- OZET

Write-Host ""
Write-Host "=== MATRIS BITTI ===" -ForegroundColor Yellow
Write-Host "sure: $([int]((New-TimeSpan -Start $started -End (Get-Date)).TotalMinutes)) dakika"

$invalid = @()
Get-ChildItem -Path (Join-Path $ProjectRoot 'runs') -Directory |
  ForEach-Object {
    $meta = Join-Path $_.FullName 'meta.txt'
    if (Test-Path $meta) {
        $content = Get-Content $meta
        if ($content -match 'verdict=INVALID') {
            $reason = ($content | Where-Object { $_ -match '^invalid_reasons=' }) -replace 'invalid_reasons=',''
            $invalid += "$($_.Name)  ($reason)"
        }
    }
  }

if ($invalid.Count -gt 0) {
    Write-Host ""
    Write-Host "GECERSIZ KOSULAR ($($invalid.Count)) -- bunlari tekrar kos:" -ForegroundColor Red
    $invalid | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
} else {
    Write-Host "tum kosular gecerli" -ForegroundColor Green
}