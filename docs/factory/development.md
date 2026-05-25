# Development

## Backend

Maven-root: `newsfeedbackend/newsfeedbackend/`

```bash
# Build + unit tests
cd newsfeedbackend/newsfeedbackend
mvn clean package

# Alleen tests
mvn test

# Draaien (vereist lokale env-vars, zie secrets-local.md)
mvn spring-boot:run
```

## Frontend

```bash
cd frontend

# Lokaal (web, backend op localhost:8080)
make serve-ext

# Of direct:
flutter run -d web-server --web-port 3000 --dart-define=API_BASE_URL=http://localhost:8080

# Web-release build
make build-web

# Android APK (productie-backend)
make build-apk-ext
```

## Conventions

- **Modules**: één module per domein in `newsfeedbackend/newsfeedbackend/src/main/kotlin/…/<module>/`
- **Flyway**: nieuwe migratie altijd `V<n+1>__<omschrijving>.sql`; nooit bestaande migraties aanpassen
- **Tests**: JUnit 5 + Mockito in `src/test/`; integratietests gebruiken een echte DB (geen mocks)
- **Flutter**: providers in `frontend/lib/providers/`, schermen in `frontend/lib/screens/`
- **Commits**: Nederlandse of Engelse commit-messages, issue-key als prefix waar van toepassing
