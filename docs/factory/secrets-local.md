# Local Secrets

Variabelen die nodig zijn om de backend lokaal te draaien. Zet echte waarden in een `.env`-bestand of exporteer ze voor `mvn spring-boot:run`. Nooit echte waarden in git.

| Variabele | Verplicht | Beschrijving |
|-----------|-----------|-------------|
| `PNF_DATABASE_URL` / `SPRING_DATASOURCE_URL` | Ja | JDBC-URL van de Postgres-database (Neon of lokale Postgres). Voorbeeld: `jdbc:postgresql://host/dbname?sslmode=require&user=...&password=...` |
| `PNF_ANTHROPIC_API_KEY` | Ja | Anthropic API-sleutel voor Claude. Ophalen uit de Anthropic Console. |
| `PNF_TAVILY_API_KEY` | Nee | Tavily websearch API-sleutel (alleen nodig voor ad-hoc zoekopdrachten). |
| `PNF_OPENAI_API_KEY` | Nee | OpenAI TTS API-sleutel (alleen nodig voor podcast audio via OpenAI). |
| `PNF_ELEVENLABS_API_KEY` | Nee | ElevenLabs TTS API-sleutel (alternatief voor podcast audio). |
| `APP_JWT_SECRET` | Ja | Willekeurige string voor JWT-signing. Minimaal 32 tekens. |
| `APP_DATA_DIR` | Nee | Map voor dataopslag (standaard: `/data`). Lokaal: stel in op een schrijfbare map. |

## Lokale Postgres starten (optioneel)

Als je geen Neon-account hebt, start je een lokale Postgres via Docker:

```bash
docker run -d --name pnf-db \
  -e POSTGRES_DB=newsfeed \
  -e POSTGRES_USER=newsfeed \
  -e POSTGRES_PASSWORD=newsfeed \
  -p 5432:5432 postgres:16
```

Dan: `PNF_DATABASE_URL=jdbc:postgresql://localhost:5432/newsfeed?user=newsfeed&password=newsfeed`
