# Google-login instellen

De applicatie gebruikt dezelfde opzet als Robbert's assistent: Flutter ontvangt een Google
ID-token, de backend verifieert signature/audience/issuer/expiry en laat alleen expliciet
geconfigureerde accounts toe. De mapping `email=username` bewaart de bestaande interne username
en daarmee alle persoonlijke data.

## 1. Google Web OAuth-client hergebruiken

Gebruik bij voorkeur het bestaande Google Cloud-project en de bestaande **Web application**
OAuth-client van Robbert's assistent.

1. Open [Google Auth Platform – Clients](https://console.cloud.google.com/auth/clients).
2. Selecteer hetzelfde project als voor Robbert's assistent.
3. Open de bestaande Web application-client.
4. Voeg bij **Authorized JavaScript origins** toe:
   - `https://news.vdzonsoftware.nl`
   - optioneel voor lokaal ontwikkelen: `http://localhost:3000`
5. Sla op en kopieer de client-ID (`...apps.googleusercontent.com`). Redirect-URI's zijn voor
   deze popup/callback-flow niet nodig.

Als de app op **Testing** staat, open dan
[Google Auth Platform – Audience](https://console.cloud.google.com/auth/audience) en voeg
`robbertvdzon@gmail.com` toe als test user. Bij **In production** is dit niet nodig; de backend-
allowlist blijft in beide gevallen bepalend.

## 2. Backend- en buildconfig invullen

Voeg aan `deploy/secrets-cluster.env` toe:

```dotenv
PNF_GOOGLE_CLIENT_ID=<de-bestaande-web-client-id>.apps.googleusercontent.com
PNF_GOOGLE_USERS=robbertvdzon@gmail.com=robbert
```

Meer gebruikers komen later komma-gescheiden in dezelfde regel, bijvoorbeeld:

```dotenv
PNF_GOOGLE_USERS=robbertvdzon@gmail.com=robbert,iemand@example.com=iemand
```

Zet dezelfde client-ID als niet-geheime GitHub Actions-variable:

1. Open [repository Actions variables](https://github.com/robbertvdzon/personal-news-feed-by-claude-code/settings/variables/actions).
2. Kies **New repository variable**.
3. Naam: `GOOGLE_CLIENT_ID`; waarde: dezelfde Web client-ID.

## 3. Vaste Android signing-key maken

Google koppelt een Android OAuth-client aan de package name én SHA-1 van de signing-key. De oude
workflow gebruikte een per-runner debug-key; daarom is een vaste release-key nodig.

```bash
keytool -genkeypair -v \
  -keystore personal-news-feed-release.keystore \
  -alias personal-news-feed \
  -keyalg RSA -keysize 2048 -validity 10000

keytool -list -v \
  -keystore personal-news-feed-release.keystore \
  -alias personal-news-feed
```

Bewaar het keystore-bestand en wachtwoord ook buiten GitHub als herstelbackup. Noteer de getoonde
SHA-1. Maak daarna drie repository secrets via
[GitHub Actions secrets](https://github.com/robbertvdzon/personal-news-feed-by-claude-code/settings/secrets/actions):

- `ANDROID_KEYSTORE_BASE64`: uitvoer van
  `base64 < personal-news-feed-release.keystore | tr -d '\n'`
- `ANDROID_KEYSTORE_PASSWORD`: het gekozen keystore/key-wachtwoord
- `ANDROID_KEY_ALIAS`: `personal-news-feed`

Omdat de signing-key verandert ten opzichte van oudere debug-gesigneerde APK's moet de bestaande
app één keer van Android worden verwijderd voordat de eerste nieuwe APK geïnstalleerd kan worden.
De serverdata blijft behouden; alleen lokale cache/sessie verdwijnt.

Een apart Android-OAuth-client (gekoppeld aan de SHA-1 hierboven) is **niet nodig**: de app geeft
op Android de Web client-ID door als `serverClientId` (zie `auth_provider.dart`), en dat is precies
zoals `google_sign_in` een ID-token voor de backend ophaalt zonder een geregistreerd Android-client
— zelfde aanpak als de software-factory-dashboard-app. De vaste keystore hierboven is puur nodig
zodat Android updates niet als "nieuwe app" behandelt (elke build anders ondertekend zou anders
telkens een verwijder-herinstalleer-stap vergen); met Google-login heeft dat niets te maken.

## 4. Cluster-secret sealen en uitrollen

```bash
./deploy/seal-secrets.sh
git add deploy/base/sealed-secret-api-keys.yaml
```

Na commit/push en ArgoCD-sync:

```bash
oc rollout restart -n personal-news-feed deploy/backend
oc rollout status -n personal-news-feed deploy/backend
```

Start daarna de APK-workflow of push een frontendwijziging. Test zowel
`https://news.vdzonsoftware.nl` als de nieuwe APK met `robbertvdzon@gmail.com`.
