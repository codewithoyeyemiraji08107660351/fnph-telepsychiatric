<#
.SYNOPSIS
    Full logical backup of the FNPH database with the binary log position recorded.

.DESCRIPTION
    The recorded binary log position is the part that matters. Restoring the
    dump alone rewinds to the last backup; replaying the binary log from the
    recorded position brings it forward to any chosen moment. That is what
    makes a 15-minute recovery point real rather than aspirational.

.EXAMPLE
    .\scripts\backup.ps1
    .\scripts\backup.ps1 -OutputDir D:\backups
#>
param(
    [string]$DbHost   = $(if ($env:DB_HOST) { $env:DB_HOST } else { "127.0.0.1" }),
    [int]   $Port     = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
    [string]$Database = $(if ($env:DB_NAME) { $env:DB_NAME } else { "telepsychiatric" }),
    [string]$User     = $(if ($env:DB_BACKUP_USER) { $env:DB_BACKUP_USER } else { "root" }),
    [string]$OutputDir = ".\backups",
    [int]   $RetainDays = 30
)

$ErrorActionPreference = "Stop"

$password = if ($env:DB_BACKUP_PASSWORD) { $env:DB_BACKUP_PASSWORD } else { $env:DB_ROOT_PASSWORD }
if (-not $password) {
    Write-Error "Set DB_BACKUP_PASSWORD or DB_ROOT_PASSWORD before running."
    exit 1
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$dump  = Join-Path $OutputDir "$($Database)_$stamp.sql"

Write-Host "Backing up $Database to $dump"

# --single-transaction  consistent snapshot without locking out the application
# --source-data=2       writes the binary log position into the dump
# --routines --triggers --events  objects a plain dump silently drops
& mysqldump `
    --host=$DbHost --port=$Port `
    --user=$User "--password=$password" `
    --single-transaction `
    --source-data=2 `
    --routines --triggers --events `
    --hex-blob `
    --default-character-set=utf8mb4 `
    --databases $Database | Out-File -FilePath $dump -Encoding utf8

if ($LASTEXITCODE -ne 0) { Write-Error "mysqldump failed with exit code $LASTEXITCODE"; exit 1 }

# Compress
$archive = "$dump.zip"
Compress-Archive -Path $dump -DestinationPath $archive -Force
Remove-Item $dump

# A checksum turns "the file exists" into "the file is intact".
$hash = (Get-FileHash -Path $archive -Algorithm SHA256).Hash
"$hash  $(Split-Path $archive -Leaf)" | Set-Content -Path "$archive.sha256" -Encoding ASCII

$sizeMb = [math]::Round((Get-Item $archive).Length / 1MB, 2)
Write-Host "Done: $archive ($sizeMb MB)"
Write-Host "Checksum: $hash"

Write-Host "Pruning backups older than $RetainDays days"
Get-ChildItem -Path $OutputDir -Filter "$($Database)_*.zip*" |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$RetainDays) } |
    Remove-Item -Force

Write-Host ""
Write-Host "A backup on the same machine as the database is not a backup."
Write-Host "Copy this file off-box, encrypted, before you call the job done."
