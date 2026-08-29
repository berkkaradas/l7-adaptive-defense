<#
  Tek bir deney kosusu: sifirla -> kosulu ayarla -> lag bekle -> offset al
  -> k6 kostur -> kanit topla -> analiz et.

  Ornek:
    .\scripts\run_once.ps1 -Scenario s2 -Condition adaptive -Run 1
    .\scripts\run_once.ps1 -Scenario s4 -Condition baseline -Run 3 -Smoke
#>
param(
    [Parameter(Mandatory=$true)][ValidateSet('s1','s2','s3','s4')][string]$Scenario,
    [Parameter(Mandatory=$true)][ValidateSet('adaptive','baseline')][string]$Condition,
    [Parameter(Mandatory=$true)][int]$Run,
    [switch]$Smoke
)

$ErrorActionPreference = 'Continue'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$ScriptMap = @{
    's1' = 'k6/s1_baseline.js'
    's2' = 'k6/s2_bruteforce.js'
    's3' = 'k6/s3_nat.js'
    's4' = 'k6/s4_degradation.js'
}

# Faz sureleri config.js ile AYNI olmali. Arizanin ne zaman baslayacagini
# bilmemiz gerekiyor (sadece S4 icin) ve gateway loglarini ne kadar geriden
# tarayacagimizi hesaplamak icin toplam sure lazim.
if ($Smoke) { $AttackStart = 20;  $AttackDuration = 20;  $TotalRun = 50  }
else        { $AttackStart = 120; $AttackDuration = 180; $TotalRun = 360 }

$RunId  = "{0}_{1}_{2:d2}" -f $Scenario.ToUpper(), $Condition, $Run
if ($Smoke) { $RunId = "smoke_$RunId" }   # gercek kosularla asla cakismasin
$RunDir = Join-Path $ProjectRoot "runs\$RunId"
$RunDir = Join-Path $ProjectRoot "runs\$RunId"
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

$invalidReasons = @()

function Write-Step($msg) { Write-Host "[$RunId] $msg" -ForegroundColor Cyan }
function Write-Bad($msg)  { Write-Host "[$RunId] $msg" -ForegroundColor Red }

# --------------------------------------------------------------- yardimcilar

function Get-DecisionOffset {
    # Cikti: "l7.decisions:0:323" -> 323
    $out = docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh `
        --bootstrap-server localhost:9092 --topic l7.decisions 2>$null
    $line = $out | Where-Object { $_ -match '^l7\.decisions:' } | Select-Object -First 1
    if ($line) { return [int]($line -split ':')[2] }
    return -1
}

function Get-ConsumerLag {
    # LAG 6. sutun (index 5). Consumer henuz katilmadiysa hic satir donmez.
    $out = docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --describe --group risk-engine 2>$null
    $rows = $out | Where-Object { $_ -match '^risk-engine\s' }
    if (-not $rows) { return $null }
    $total = 0
    foreach ($r in $rows) {
        $lag = ($r -split '\s+')[5]
        if ($lag -match '^\d+$') { $total += [int]$lag }
    }
    return $total
}

function Wait-GatewayReady {
    param([int]$TimeoutSec = 120)
    # HTTP'nin GERCEKTEN servis edildigini dogruluyoruz. TCP portunun
    # baglanmasi yetmiyor: Netty portu Spring context'i tamamlanmadan
    # acıyor ve o aralikta gelen istek EOF ile kapaniyor -- ilk matris
    # denemesinde S1/S2/S3 baseline'i tam olarak boyle dustu (k6 exit 107).
    #
    # Yol BILEREK eslesmeyen: Spring Cloud Gateway route'u filtre
    # zincirinden ONCE cozuyor; eslesmezse 404 donup zinciri hic
    # calistirmiyor (4.6.1). Olcum: 3 yoklama, sinyal offseti degismedi.
    # /orders gibi gercek bir yola atsaydik UNRESOLVED kimligi skorlanir,
    # kosu daha baslamadan puan birikirdi.
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $consecutive = 0
    while ((Get-Date) -lt $deadline) {
        $ok = $false
        try {
            Invoke-WebRequest -Uri 'http://localhost:8080/__ready' `
                -TimeoutSec 3 -UseBasicParsing | Out-Null
            $ok = $true
        } catch {
            # 404 da basarilidir: HTTP statusu donduyse sunucu ayakta.
            # Baglanti hatasinda Response null olur -- o zaman hazir degil.
            if ($_.Exception.Response) { $ok = $true }
        }
        if ($ok) {
            $consecutive++
            if ($consecutive -ge 2) { return $true }   # iki ust uste, gecici aralik olmasin
        } else {
            $consecutive = 0
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-LagZero {
    param([int]$TimeoutSec = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $lag = Get-ConsumerLag
        if ($null -ne $lag -and $lag -eq 0) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

# --------------------------------------------------------- 1. STATE SIFIRLA

Write-Step "state sifirlaniyor (restart risk-engine + gateway)"
docker compose restart risk-engine gateway | Out-Null

if (-not (Wait-GatewayReady)) {
    Write-Bad "gateway ayaga kalkmadi -- kosu GECERSIZ"
    $invalidReasons += 'gateway-not-ready'
}

# ------------------------------------------------------------ 2. KOSUL AYARI

if ($Condition -eq 'baseline') {
    Write-Step "BASELINE: risk-engine durduruluyor"
    docker compose stop risk-engine | Out-Null
} else {
    # 3. LAG SIFIRLANSIN -- sadece adaptive'te anlamli.
    # Onceki baseline kosusunda Gateway sinyal yazmaya devam etti ama kimse
    # tuketmedi. O birikmis yigin islenmeden baslarsak motor geriden gelir ve
    # TTD sahte olarak sisir (11.4).
    Write-Step "ADAPTIVE: consumer lag sifirlanmasi bekleniyor"
    if (-not (Wait-LagZero)) {
        Write-Bad "lag sifirlanmadi -- kosu GECERSIZ"
        $invalidReasons += 'lag-not-cleared'
    }
}

# -------------------------------------------------------- 4. BASLANGIC OFFSET

$startOffset = Get-DecisionOffset
Write-Step "baslangic offset: $startOffset"
if ($startOffset -lt 0) { $invalidReasons += 'offset-unavailable' }

# ------------------------------------------------------------- 5. ARIZA (S4)

$outageJob = $null
if ($Scenario -eq 's4') {
    Write-Step "ariza enjeksiyonu arka planda basliyor"
    $outageJob = Start-Job -ScriptBlock {
        param($proj, $start, $dur)
        Set-Location $proj
        & (Join-Path $proj 'scripts\inject_outage.ps1') -AttackStart $start -AttackDuration $dur
    } -ArgumentList $ProjectRoot, $AttackStart, $AttackDuration
}

# --------------------------------------------------------------- 6. K6 KOSTUR

$startedAt = Get-Date
Write-Step "k6 basliyor: $($ScriptMap[$Scenario])"

$k6Args = @('run')
if ($Smoke) { $k6Args += @('-e', 'SMOKE=1') }
$k6Args += @(
    '--summary-export', (Join-Path $RunDir 'k6_summary.json'),
    '--out', ('json=' + (Join-Path $RunDir 'k6_raw.json')),
    $ScriptMap[$Scenario]
)
& k6 @k6Args
if ($LASTEXITCODE -ne 0) {
    Write-Bad "k6 hata koduyla cikti ($LASTEXITCODE) -- kosu GECERSIZ"
    $invalidReasons += "k6-exit-$LASTEXITCODE"
}
$finishedAt = Get-Date

# Ariza isini topla ve orders-service'in kesin ayakta oldugundan emin ol.
if ($outageJob) {
    Wait-Job $outageJob -Timeout 60 | Out-Null
    Receive-Job $outageJob | Out-Null
    Remove-Job $outageJob -Force
    docker compose start orders-service | Out-Null
}

# ---------------------------------------------------------- 7. KANITI TOPLA

$endOffset = Get-DecisionOffset
$count = 0
if ($endOffset -ge 0 -and $startOffset -ge 0) { $count = $endOffset - $startOffset }
Write-Step "bitis offset: $endOffset  (bu kosuda $count karar)"

$decisionsPath = Join-Path $RunDir 'decisions.jsonl'
if ($count -gt 0) {
    $dump = docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh `
        --bootstrap-server localhost:9092 --topic l7.decisions `
        --partition 0 --offset $startOffset --max-messages $count --timeout-ms 20000 2>$null
    $dump | Where-Object { $_ -match '^\{' } | Out-File -FilePath $decisionsPath -Encoding utf8
} else {
    # Bos dosya: analiz script'i eksik dosyada duruyor, bosta durmuyor.
    New-Item -ItemType File -Force -Path $decisionsPath | Out-Null
}

$lagAfter = Get-ConsumerLag
$sinceSec = [int]((New-TimeSpan -Start $startedAt -End (Get-Date)).TotalSeconds) + 10
$dropLines = docker compose logs --since "${sinceSec}s" gateway 2>$null |
             Select-String 'dropped .* signal'
$dropCount = 0
if ($dropLines) { $dropCount = $dropLines.Count }

if ($Condition -eq 'baseline') {
    Write-Step "risk-engine geri baslatiliyor"
    docker compose start risk-engine | Out-Null
}

$verdict = 'VALID'
if ($invalidReasons.Count -gt 0) { $verdict = 'INVALID' }

@(
    "run_id=$RunId"
    "scenario=$Scenario"
    "condition=$Condition"
    "repeat=$Run"
    "smoke=$($Smoke.IsPresent)"
    "started_at=$($startedAt.ToString('s'))"
    "finished_at=$($finishedAt.ToString('s'))"
    "decisions_offset_start=$startOffset"
    "decisions_offset_end=$endOffset"
    "decisions_count=$count"
    "consumer_lag_after=$lagAfter"
    "gateway_drop_reports=$dropCount"
    "verdict=$verdict"
    "invalid_reasons=$($invalidReasons -join ',')"
) | Out-File -FilePath (Join-Path $RunDir 'meta.txt') -Encoding utf8

# ------------------------------------------------------------------ 8. ANALIZ

if ($verdict -eq 'VALID') {
    Write-Step "analiz calisiyor"
    python analysis/analyze_run.py $RunDir
} else {
    Write-Bad "GECERSIZ ($($invalidReasons -join ', ')) -- analiz atlandi"
}

Write-Step "bitti: $verdict"