# Local Secrets — Personal News Feed

Vereiste environment-variabelen voor lokaal draaien van de backend. Zet echte waarden nooit in git.

## Backend env-vars

| Variabele | Doel | Hoe te verkrijgen |
|-----------|------|------------------|
| `PNF_DATABASE_URL` | PostgreSQL connection string | Neon dashboard → connection string, bv. `jdbc:postgresql://ep-xxx.neon.tech/neondb?user=…&password=…&sslmode=require` |
| `JWT_SECRET` | JWT signing key (≥ 32 tekens) | `openssl rand -base64 48` |
| `PNF_GOOGLE_CLIENT_ID` | Google Web OAuth client-ID; valideert de audience van ID-tokens | Google Cloud Console → APIs & Services → Credentials |
| `PNF_GOOGLE_USERS` | Komma-gescheiden `email=username`-allowlist | Bijvoorbeeld `robbertvdzon@gmail.com=robbert` |
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
| `GOOGLE_CLIENT_ID` | Dezelfde Web OAuth client-ID als `PNF_GOOGLE_CLIENT_ID` (via `--dart-define`) |

Bij lokaal testen: `--dart-define=API_BASE_URL=http://host.docker.internal:8080` (vanuit devcontainer) of `http://localhost:8080` (native host), plus `--dart-define=GOOGLE_CLIENT_ID=<client-id>`.

De mapping houdt bewust Google-identiteit en interne username apart. Zo logt
`robbertvdzon@gmail.com` in als de bestaande gebruiker `robbert` en blijven alle bestaande
feed-, RSS- en podcastgegevens bereikbaar. Een volgende gebruiker voeg je komma-gescheiden toe,
bijvoorbeeld `PNF_GOOGLE_USERS=robbertvdzon@gmail.com=robbert,iemand@example.com=iemand`.
