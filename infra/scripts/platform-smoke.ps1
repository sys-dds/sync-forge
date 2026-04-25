param(
    [string]$ApiBaseUrl = $env:PLATFORM_API_BASE_URL,
    [string]$WebhookSinkUrl = $env:PLATFORM_WEBHOOK_SINK_URL,
    [string]$ApiKey = $env:PLATFORM_API_KEY,
    [string]$WorkspaceSlug = $env:PLATFORM_WORKSPACE_SLUG
)

if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
    $ApiBaseUrl = "http://localhost:8080"
}
if ([string]::IsNullOrWhiteSpace($WebhookSinkUrl)) {
    $WebhookSinkUrl = "http://localhost:8090"
}

Set-StrictMode -Version Latest

$composeFile = Join-Path $PSScriptRoot "..\docker-compose\docker-compose.platform.yml"
# Standalone mode only: validate and run the self-contained platform compose file.
docker compose -f $composeFile config | Out-Null
docker compose -f $composeFile up -d --build | Out-Null

& "$PSScriptRoot\\wait-for-http.ps1" -Url "$ApiBaseUrl/actuator/health/readiness"
Invoke-RestMethod -Uri "$ApiBaseUrl/actuator/health" | Out-Null
& "$PSScriptRoot\\wait-for-http.ps1" -Url $WebhookSinkUrl

if (-not [string]::IsNullOrWhiteSpace($ApiKey)) {
    $headers = @{ "X-API-Key" = $ApiKey }
    if (-not [string]::IsNullOrWhiteSpace($WorkspaceSlug)) {
        $headers["X-Workspace-Slug"] = $WorkspaceSlug
    }
    Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/workspaces/current" -Headers $headers | Out-Null
}
