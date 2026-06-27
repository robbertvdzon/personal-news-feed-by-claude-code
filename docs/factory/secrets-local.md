# Local Secrets — Personal News Feed

Vereiste environment-variabelen voor lokaal draaien van de backend. Zet echte waarden nooit in git.

## Backend env-vars

| Variabele | Doel | Hoe te verkrijgen |
|-----------|------|------------------|
| `PNF_DATABASE_URL` | PostgreSQL connection string | Neon dashboard → connection string, bv. `jdbc:postgresql://ep-xxx.neon.tech/neondb?user=…&password=…&sslmode=require` |
| `JWT_SECRET` | JWT signing key (≥ 32 tekens) | `openssl rand -base64 48` |
| `PNF_OPENAI_API_KEY` | OpenAI API-sleutel (alle AI-tekst, transcriptie, TTS) | platform.openai.com → API Keys |
| `PNF_TAVILY_API_KEY` | Tavily websearch-sleutel | app.tavily.com → API Keys |
| `PNF_ELEVENLABS_API_KEY` | ElevenLabs TTS-sleutel (optioneel) | elevenlabs.io → Profile → API Key |

## Lokale `.env`-aanpak

Maak een bestand `newsfeedbackend/newsfeedbackend/.env` (gitignored) en laad het bij het starten:

```bash
export $(cat .env | xargs)
mvn spring-boot:run
```

Of gebruik IntelliJ's Run-configuratie → Environment Variables.

## Frontend env-var

| Variabele | Doel |
|-----------|------|
| `API_BASE_URL` | Backend base URL (via `--dart-define`; default in code `http://localhost:8080`, prod-builds zetten `https://news.vdzonsoftware.nl`) |

Bij lokaal testen: `--dart-define=API_BASE_URL=http://host.docker.internal:8080` (vanuit devcontainer) of `http://localhost:8080` (native host).
