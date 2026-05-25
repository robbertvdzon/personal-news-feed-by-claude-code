# Local Secrets

Kopieer `deploy/secrets-cluster.env.example` naar `deploy/secrets-cluster.env`
en vul de waarden in. Die kopie is gitignored.

Voor lokaal draaien van de backend zijn de volgende env-vars vereist:

| Variabele | Beschrijving | Ophalen via |
|---|---|---|
| `PNF_DATABASE_URL` | JDBC-URL naar PostgreSQL (Neon) | Neon-console → project → connection string |
| `PNF_ANTHROPIC_API_KEY` | Anthropic API-sleutel (`sk-ant-…`) | console.anthropic.com → API Keys |
| `PNF_TAVILY_API_KEY` | Tavily search API-sleutel (`tvly-…`) | app.tavily.com → API |
| `PNF_OPENAI_API_KEY` | OpenAI API-sleutel (optioneel, Whisper) | platform.openai.com |
| `JWT_SECRET` | JWT signing key, ≥32 karakters | `openssl rand -base64 48` |

Stel ze in via `application-local.properties` of als systeem-env-vars voordat
je `mvn spring-boot:run` uitvoert.

De Flutter-frontend heeft geen eigen secrets — hij praat via JWT met de backend.
