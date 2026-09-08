<#
.SYNOPSIS
    Restore a backup, verify its checksum first, and report how long it took.

.DESCRIPTION
    The elapsed time is the point. The recovery time target is two hours, and
    the only way to know whether that is real is to measure a restore on the
    hardware you actually run. Do this monthly.

.EXAMPLE
    .\scripts\restore.ps1 -BackupFile .\backups\telepsychiatric_20260906T120000Z.sql.zip
    .\scripts\restore.ps1 -BackupFile <file> -Into telepsychiatric_restore_test
#>
param(
    [Parameter(Mandatory=$true)][string]$BackupFile,
    [string]$Into = "",
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { "127.0.0.1" }),
    [int]   $Port   = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
    [string]$User   = $(if ($env:DB_BACKUP_USER) { $env:DB_BACKUP_USER } else { "root" })
)

$ErrorActionPreference = "Stop"

$password = if ($env:DB_BACKUP_PASSWORD) { $env:DB_BACKUP_PASSWORD } else { $env:DB_ROOT_PASSWORD }
if (-not $password) { Write-Error "Set DB_BACKUP_PASSWORD or DB_ROOT_PASSWORD."; exit 1 }
if (-not (Test-Path $BackupFile)) { Write-Error "Not found: $BackupFile"; exit 1 }

$checksumFile = "$BackupFile.sha256"
if (Test-Path $checksumFile) {
    Write-Host "Verifying checksum"
    $expected = (Get-Content $checksumFile -Raw).Split()[0].Trim()
    $actual   = (Get-FileHash -Path $BackupFile -Algorithm SHA256).Hash
    if ($expected -ne $actual) {
        Write-Error "CHECKSUM FAILED. Do not restore this file."
        exit 1
    }
    Write-Host "  intact"
} else {
    Write-Warning "No .sha256 alongside this backup; integrity unverified"
}

$work = Join-Path $env:TEMP "fnph_restore_$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $work | Out-Null
Expand-Archive -Path $BackupFile -DestinationPath $work -Force
$sql = (Get-ChildItem -Path $work -Filter *.sql | Select-Object -First 1).FullName

$start = Get-Date

if ($Into) {
    Write-Host "Restoring into $Into"
    & mysql --host=$DbHost --port=$Port --user=$User "--password=$password" `
        -e "DROP DATABASE IF EXISTS ``$Into``; CREATE DATABASE ``$Into`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

    # Strip CREATE DATABASE and USE so the dump lands where we asked rather
    # than overwriting the database it came from.
    $filtered = Join-Path $work "filtered.sql"
    Get-Content $sql | Where-Object { $_ -notmatch '^CREATE DATABASE' -and $_ -notmatch '^USE ' } |
        Set-Content -Path $filtered -Encoding utf8
    Get-Content $filtered -Raw | & mysql --host=$DbHost --port=$Port --user=$User "--password=$password" $Into
} else {
    Write-Host "Restoring into the database named in the dump"
    Get-Content $sql -Raw | & mysql --host=$DbHost --port=$Port --user=$User "--password=$password"
}

$elapsed = [int]((Get-Date) - $start).TotalSeconds
Write-Host "Restore completed in ${elapsed}s"
if ($elapsed -gt 7200) { Write-Warning "Exceeded the two-hour recovery time target" }

$verifyDb = if ($Into) { $Into } else {
    ((Select-String -Path $sql -Pattern '^USE `(.+)`;' | Select-Object -First 1).Matches.Groups[1].Value)
}
Write-Host "Verifying $verifyDb"
& mysql --host=$DbHost --port=$Port --user=$User "--password=$password" -N -e @"
SELECT CONCAT('  tables: ', COUNT(*)) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$verifyDb';
SELECT CONCAT('  centres: ', COUNT(*)) FROM ``$verifyDb``.centres;
SELECT CONCAT('  flyway version: ', MAX(version)) FROM ``$verifyDb``.flyway_schema_history WHERE success=1;
"@

Remove-Item -Recurse -Force $work
