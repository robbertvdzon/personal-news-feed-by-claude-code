# Scenario: cleanup

## Doel
De e2e-test-user en al z'n data verwijderen na afloop van een testrun, zodat we geen orphaned accounts in `data/users.json` of vergeten mappen op disk laten staan. Wordt **altijd als laatste** gedraaid.

## Voorwaarden
- `start-scenario` heeft een unieke username aangemaakt in deze run (formaat `e2e_<DATETIME>`).
- De runner heeft die username onthouden of kan 'm afleiden uit `data/users.json`.

## Stappen

### 1. Vind de e2e-user
```bash
USER=$(python3 -c "
import json
users = json.load(open('newsfeedbackend/newsfeedbackend/data/users.json'))
e2es = [u['username'] for u in users if u['username'].startswith('e2e_')]
print(e2es[-1] if e2es else '')
")
echo "Cleaning up user: $USER"
```

### 2. Verwijder de user-map
```bash
rm -rf "newsfeedbackend/newsfeedbackend/data/users/$USER"
```
Dit ruimt op:
- `rss_items.json`, `feed_items.json`, `news_requests.json`, `podcasts.json`
- `settings.json`, `rss_feeds.json`, `topic_history.json`
- `audio/` map met eventuele MP3-bestanden

### 3. Verwijder de user uit `users.json`
```bash
python3 -c "
import json
path = 'newsfeedbackend/newsfeedbackend/data/users.json'
users = json.load(open(path))
users = [u for u in users if u['username'] != '$USER']
with open(path, 'w') as f:
    json.dump(users, f, indent=2)
"
```

### 4. Verifieer
- `ls newsfeedbackend/newsfeedbackend/data/users/$USER` → "No such file or directory"
- Grep naar `$USER` in `users.json` → geen output

## Verwacht resultaat

- Geen `e2e_…` regel meer in `data/users.json`.
- Geen `data/users/e2e_…` map.
- Backend draait door — verwijderen tijdens runtime is veilig omdat de repository bij elke call het bestand opnieuw inleest.

## Notities voor de runner

- Niet inloggen meer met de e2e-user na cleanup (token blijft technisch werken tot expiratie maar elke API-call zal 404'en op de user-data).
- Als er meerdere `e2e_…` users in `users.json` staan (van eerdere mislukte runs), cleanup hier alleen de huidige run; de rest mag blijven of handmatig worden opgeruimd.
- Cleanup hoeft géén data van robbert of andere echte users te raken — filter strict op `e2e_`-prefix.
