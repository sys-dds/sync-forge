param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $InputFile)) {
    throw "Backup file not found: $InputFile"
}

docker cp $InputFile "link-platform-postgres:/tmp/link-platform-backup.dump"
docker exec link-platform-postgres sh -c "psql -U link_platform -d link_platform -c 'DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;'"
docker exec link-platform-postgres sh -c "pg_restore -U link_platform -d link_platform /tmp/link-platform-backup.dump"
docker exec link-platform-postgres rm /tmp/link-platform-backup.dump

Write-Host "Restore completed from $InputFile"
