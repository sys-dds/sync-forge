# SyncForge

SyncForge is a realtime collaboration backend foundation for room membership, WebSocket room lifecycle, and connection tracking.

## Local Run

```powershell
docker compose -f infra/docker-compose/docker-compose.yml config
cd apps/api
.\mvnw.cmd test
.\mvnw.cmd -DskipTests compile
cd ..\..
docker compose -f infra/docker-compose/docker-compose.yml up -d --build
docker compose -f infra/docker-compose/docker-compose.yml down -v
```
