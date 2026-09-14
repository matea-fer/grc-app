# Pre-flight (Windows PowerShell): verzije alata + zauzetost porta baze, u JEDNOM prolazu.
# Pokreni iz korijena startera i zalijepi AI-ju CIJELI ispis:
#   powershell -ExecutionPolicy Bypass -File scripts\preflight.ps1

$ErrorActionPreference = "Continue"

$port = "5432"
if (Test-Path .env) {
    $line = Select-String -Path .env -Pattern '^DB_PORT=' | Select-Object -Last 1
    if ($line) { $port = ($line.Line -split '=', 2)[1].Trim() }
}
if ([string]::IsNullOrWhiteSpace($port)) { $port = "5432" }

function Show($label, $block) {
    Write-Host -NoNewline ("{0,-9}" -f $label)
    try { $out = & $block 2>&1; if ($out) { Write-Host ($out | Select-Object -First 1) } else { Write-Host "NEMA" } }
    catch { Write-Host "NEMA" }
}

Write-Host "==================== VERZIJE ===================="
Show "Java:"    { (java -version 2>&1) }
Show "Gradle:"  { (& .\backend\gradlew.bat -v 2>$null); if (-not $?) { gradle -v 2>$null } | Select-String '^Gradle' }
Show "Node:"    { node -v }
Show "npm:"     { npm -v }
Show "Angular:" { (npx --yes @angular/cli version 2>$null | Select-String 'Angular CLI') }
Show "psql:"    { psql --version }
Show "docker:"  { docker --version }

Write-Host ""
Write-Host "============== PORT BAZE ($port) ==============="
# 1) Docker kontejner koji vec objavljuje ovaj port.
$busy = docker ps --filter "publish=$port" --format "{{.Names}}  ({{.Image}})  {{.Ports}}" 2>$null
# 2) BILO KOJI proces koji slusa na portu - hvata i NATIVNI Postgres (Windows servis),
#    kojeg Docker filter iznad NE vidi. Prije diga baze ovdje ne smije biti nikoga.
$listen = $null
try {
    $conns = Get-NetTCPConnection -LocalPort ([int]$port) -State Listen -ErrorAction Stop
    $listen = $conns | ForEach-Object {
        $pname = (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        "  {0}:{1}  PID {2} ({3})" -f $_.LocalAddress, $_.LocalPort, $_.OwningProcess, $pname
    }
} catch {
    $n = netstat -ano 2>$null | Select-String "LISTENING" | Select-String (":" + $port + "\s")
    if ($n) { $listen = $n | ForEach-Object { "  " + $_.Line.Trim() } }
}
if ($busy) {
    Write-Host "ZAUZET (Docker kontejner):"
    Write-Host "  $busy"
    Write-Host "-> ugasi taj kontejner (docker stop <ime>) ILI promijeni DB_PORT u .env"
} elseif ($listen) {
    Write-Host "ZAUZET (NATIVNI proces, NE Docker) - Docker filter ovo NE vidi:"
    $listen | ForEach-Object { Write-Host $_ }
    $svc = Get-Service *postgres* -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Running' }
    if ($svc) { Write-Host ("   Nativni Postgres servis: " + (($svc | ForEach-Object Name) -join ', ')) }
    Write-Host "-> Backend bi gadao NJEGA, ne kontejner -> 28P01 password authentication failed."
    Write-Host "   Daj kontejneru drugi host-port: DB_PORT=5433 u root .env I backend/.env"
    Write-Host "   (+ u docker-compose.yml ako je hardkodiran). Ne gasi svoj nativni Postgres."
} else {
    Write-Host "slobodan (ni Docker ni nativni proces ne slusaju na $port)."
}

Write-Host ""
Write-Host "Matrica koju kostur trazi: Java 21 | Gradle 9.x | Node 20.19+/22.12+ | Angular 21 | Postgres 14+"
