param(
    [string]$OutputFile = "C:\Users\Ryzen-pc\Desktop\sys-dds\codex-scratch\link-platform-backup.dump"
)

$ErrorActionPreference = "Stop"

$outputDirectory = Split-Path -Parent $OutputFile
if (-not (Test-Path $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

docker exec link-platform-postgres sh -c "pg_dump -U link_platform -d link_platform -Fc -f /tmp/link-platform-backup.dump"
docker cp "link-platform-postgres:/tmp/link-platform-backup.dump" $OutputFile
docker exec link-platform-postgres rm /tmp/link-platform-backup.dump

Write-Host "Backup written to $OutputFile"
