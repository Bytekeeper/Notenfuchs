# Notenfuchs

Notenfuchs ist ein Open-Source-Tool zur Notenverwaltung für Lehrkräfte, entwickelt mit
[Quarkus](https://quarkus.io/). Es erlaubt Lehrkräften, Klassen, Schüler, Fächer,
gewichtete Notenkategorien (z. B. "Schriftlich" / "Mündlich"), einzelne Leistungen und
Noten zu verwalten - und berechnet automatisch gewichtete Durchschnitte pro Schüler und
Fach sowie gerundete Endnoten.

## Screenshots

| Klassenübersicht | Noteneingabe-Raster |
|---|---|
| ![Klassenliste](screenshots/class-list.png) | ![Noteneingabe-Raster](screenshots/grade-grid.png) |

![Klassendetail: Fächer und Schülerliste](screenshots/class-detail.png)

(Alle gezeigten Namen sind frei erfunden und keine echten Schüler.)

## Notenfuchs betreiben

Notenfuchs läuft als Docker-Container zusammen mit PostgreSQL. Das Einzige, was
installiert sein muss, ist [Docker](https://docs.docker.com/get-docker/) - Docker
Desktop unter Mac/Windows oder Docker Engine + das Compose-Plugin unter Linux. Es wird
keine lokale Java-, Maven- oder sonstige Toolchain benötigt, um es *auszuführen* (nur um
am Code zu arbeiten - siehe "Für die Entwicklung" weiter unten).

Wähle **Option A**, wenn du die einzige Lehrkraft bist, die diese Instanz nutzt, oder
**Option B**, wenn mehrere Lehrkräfte jeweils einen eigenen Login benötigen.

### Option A: Einzelnutzer, lokales Passwort (Standard, am einfachsten)

Ein fest eingerichteter Login mit einem von dir gewählten Passwort - sonst muss nichts
konfiguriert oder registriert werden.

```bash
git clone https://github.com/bytekeeper/Notenfuchs.git
cd Notenfuchs
cp .env.example .env      # dann .env öffnen und NOTENFUCHS_PASSWORD setzen
docker compose up
```

Das lädt ein vorgefertigtes Image von `ghcr.io/bytekeeper/notenfuchs` (von CI bei jedem
Push auf `master` veröffentlicht, siehe `.github/workflows/publish-image.yml`) und
startet es zusammen mit PostgreSQL - kein lokaler Build nötig. Sobald es läuft, öffne
`http://localhost:8080/login` und melde dich mit dem Benutzernamen `lehrer` und dem
gesetzten Passwort an (siehe "Standard: lokale integrierte Anmeldung" unter
"Authentifizierung" weiter unten, wie dieser Login funktioniert).

Um alles zu stoppen: `docker compose down` (mit `-v` wird zusätzlich das
Datenbank-Volume gelöscht).

### Option B: Mehrbenutzer, Pocket ID SSO

Wenn mehrere Lehrkräfte diese Instanz nutzen, braucht jede einen eigenen Login.
Notenfuchs delegiert dies an [Pocket ID](https://pocket-id.org), einen schlanken,
Passkey-basierten OIDC-Provider, der im selben Compose-Stack mitläuft - keine separate
Registrierung bei einem Drittanbieter nötig, und keine Passwörter zu verwalten (Pocket
ID nutzt ausschließlich WebAuthn/Passkeys). Die Einrichtung erfolgt über ein paar
manuelle Schritte in Pocket IDs eigener Admin-Oberfläche; nichts wird automatisch
vorkonfiguriert.

```bash
git clone https://github.com/bytekeeper/Notenfuchs.git
cd Notenfuchs
cp .env.example .env
```

1. **Compose auf beide Dateien verweisen lassen.** In `.env` die Zeile
   `COMPOSE_FILE=docker-compose.yml:docker-compose.oidc.yml` einkommentieren - jeder
   spätere `docker compose`-Befehl kombiniert dann automatisch den Basis-Stack mit dem
   Pocket-ID-Overlay, ohne `-f -f`-Flags. `NOTENFUCHS_PASSWORD` leer lassen - lokale
   Anmeldung und OIDC sind nie gleichzeitig aktiv.
2. **Das Secret erzeugen, das Pocket ID braucht** und in `.env` eintragen:
   `POCKET_ID_ENCRYPTION_KEY`, per `openssl rand -base64 32`.
3. **Den Stack starten:** `docker compose up`.
4. **Das Pocket-ID-Admin-Konto anlegen.** Das ist der eine Schritt, der sich nicht
   automatisieren lässt: Pocket ID hat keine Passwörter, nur WebAuthn-Passkeys, und
   deren Registrierung erfordert einen echten Browser + Authenticator. Rufe einmalig
   `<POCKET_ID_APP_URL>/setup` auf (`http://localhost:1411/setup` bei der
   Standard-`.env`) und folge den Anweisungen.
5. **Notenfuchs als OIDC-Client registrieren.** Gehe in Pocket IDs Admin-UI zu OIDC
   Clients -> New Client, setze die Callback-URL auf
   `<deine Notenfuchs-URL>/auth-callback` (standardmäßig
   `http://localhost:8080/auth-callback`) und die Logout-Callback-URL auf
   `<deine Notenfuchs-URL>/`, klappe dann "Advanced options" auf und setze das Feld
   **Client ID** exakt auf `notenfuchs` (passend zur fest codierten `OIDC_CLIENT_ID` in
   `docker-compose.oidc.yml`). Beim Speichern wird ein Client Secret erzeugt - kopiere
   es als `OIDC_CLIENT_SECRET` in die `.env` und führe dann `docker compose up -d app`
   aus, damit es übernommen wird.
6. **Zugriff gewähren.** Ein neu erstellter OIDC-Client in Pocket ID ist zunächst
   vollständig gesperrt - noch keine Benutzergruppe ist zugelassen, sodass sich niemand
   darüber anmelden kann, selbst nicht nach dem Anlegen eines Passkey-Kontos im
   nächsten Schritt. Klappe auf der Client-Seite **Allowed User Groups** auf und
   klicke entweder auf **Unrestrict** (jeder Pocket-ID-Nutzer kann sich anmelden - für
   eine Einzellehrkraft-Instanz oder einen kleinen, vertrauenswürdigen
   Haushalt/Kollegium in Ordnung) oder lege eine Gruppe an/weise eine zu, um den
   Zugriff auf genau die Lehrkräfte zu beschränken, die Notenfuchs nutzen sollen.
7. **Einen Registrierungslink verschicken.** Erzeuge auf der Users-Seite über
   "Create signup token" einen Einmal-Link und gib ihn an die Person weiter, die
   Notenfuchs tatsächlich nutzen soll (z. B. dich selbst oder eine Kollegin/einen
   Kollegen), damit sie direkt ein eigenes Passkey-Konto anlegen kann - der
   Admin-Login muss nie geteilt werden.

Standardmäßig läuft das alles auf dem eigenen Rechner über reines HTTP:
`POCKET_ID_APP_URL` in `.env` ist standardmäßig `http://localhost:1411`, und Notenfuchs
selbst ist unter `http://localhost:8080` erreichbar.

#### Hinter einem eigenen Reverse Proxy betreiben

`docker-compose.oidc.yml` geht standardmäßig davon aus, dass Pocket ID hinter einem
Reverse Proxy läuft - WebAuthn/Passkeys benötigen ohnehin einen sicheren Kontext
(HTTPS oder reines `localhost`), sodass ein nacktes, nicht proxiertes Pocket ID
sowieso kein wirklich unterstütztes Setup ist. Sein veröffentlichter Port ist nur an
`127.0.0.1` gebunden (`POCKET_ID_HOST_BIND` in `.env`, siehe unten), sodass der
einzige Zugang über etwas führt, das bereits auf derselben Maschine läuft - dein
Reverse Proxy, oder dein eigener Browser, wenn du nur lokal testest.

Falls du bereits einen Reverse Proxy betreibst (Caddy, nginx, Traefik, ...), der TLS
unter einer echten Domain terminiert - auch eine, die nur im eigenen Netzwerk
erreichbar ist, z. B. `notenfuchs.internal.example.com`, aufgelöst durch internes DNS
statt durch das öffentliche Internet - richte ihn auf die Ports `8080` (Notenfuchs)
und `1411` (Pocket ID) dieses Hosts aus und setze in `.env`:

- **`POCKET_ID_APP_URL`**: die URL, unter der dein Proxy Pocket ID erreichbar macht,
  z. B. `https://id.internal.example.com` - ohne `:1411`, da das die interne reine
  HTTP-Adresse des Containers ist, nicht das, was ein Browser oder die
  OIDC-Issuer-Prüfung je sehen sollte.
- **`TRUST_PROXY=true`**: damit Pocket ID den Forwarded-For-Headern des Proxys für die
  echte IP des Clients vertraut, statt jede Anfrage als von der IP des Proxys selbst
  kommend zu sehen. (Pocket ID akzeptiert hier alternativ auch eine kommagetrennte
  Liste vertrauenswürdiger Proxy-IPs/CIDRs, statt eines pauschalen `true`, falls du es
  auf die tatsächliche Adresse deines Proxys beschränken möchtest.)
- **`OIDC_TRUST_PROXY=true`** und **`APP_HOST_BIND=127.0.0.1`**: Hier steht auch
  Notenfuchs selbst hinter deinem Proxy, nicht nur Pocket ID - anders als
  `docker-compose.oidc.yml` wird die Basis-`docker-compose.yml` mit Option A geteilt
  (gar kein Proxy), sodass ihr Port ohne dieses Opt-in auf jedem Interface offen
  bleibt (`0.0.0.0`). `OIDC_TRUST_PROXY=true` behebt die *Schema*-Hälfte des
  Problems: ohne diese Einstellung sieht der Container vom Proxy nur reines HTTP und
  würde seine OIDC-Callback-URL als `http://...` statt `https://...` bauen, was nicht
  zu der in Pocket IDs Admin-UI registrierten Callback-URL passt (Schritt 5 oben) und
  den Login fehlschlagen lässt. Dabei wird kein vom Proxy (oder einem ihn umgehenden
  Client) gesendeter Header ausgewertet - es ist ein pauschaler "wir wissen, dass wir
  immer hinter TLS stehen"-Schalter, kein Forwarded-Header-Vertrauensentscheid, daher
  unabhängig vom obigen `TRUST_PROXY` sicher aktivierbar. Die *Host*-Hälfte braucht
  auf Notenfuchs' Seite keine Konfiguration: Quarkus baut den Host der Callback-URL
  aus dem `Host`-Header der eingehenden Anfrage, den Caddy (und die meisten Reverse
  Proxys standardmäßig) unverändert weiterreicht, sodass er bereits die vom Browser
  tatsächlich verwendete öffentliche Domain widerspiegelt - es gibt kein
  `NOTENFUCHS_APP_URL` zu setzen, anders als bei Pocket ID, das eine statisch
  konfigurierte `POCKET_ID_APP_URL` braucht, weil es diesen Wert auch für Dinge
  nutzt, die an keine einzelne Anfrage gebunden sind (den Token-Issuer, den es
  signiert, seine WebAuthn-Relying-Party-ID, sein
  `/.well-known/openid-configuration`-Dokument).
- Verwende überall dort, wo `<deine Notenfuchs-URL>` in Schritt 5 oben steht, die echte
  URL deines Proxys (nicht `localhost:8080`), z. B.
  `https://notenfuchs.internal.example.com/auth-callback`.

Falls dein Reverse Proxy auf einem *anderen* Host läuft als dieser Compose-Stack
(statt auf derselben Maschine, wovon der `127.0.0.1`-Standard oben ausgeht),
überschreibe `POCKET_ID_HOST_BIND`/`APP_HOST_BIND` mit einer Adresse, die dein Proxy
tatsächlich erreichen kann - z. B. `0.0.0.0`, um Verbindungen auf jedem Interface
anzunehmen - und stelle sicher, dass deine Firewall (nicht Compose) tatsächlich
einschränkt, wer diese Ports direkt erreichen kann; `TRUST_PROXY=true` /
`OIDC_TRUST_PROXY=true` ergeben nur Sinn, wenn der *einzige* Weg hinein wirklich über
den Proxy führt.

Pocket ID ist vollständig optional - die zugrunde liegende OIDC-Anbindung ist
provider-unabhängig, sodass du stattdessen `OIDC_ISSUER_URL` / `OIDC_CLIENT_ID` /
`OIDC_CLIENT_SECRET` auf Clerk, Keycloak, Authentik oder einen anderen
standardkonformen Provider richten und `docker-compose.oidc.yml` komplett weglassen
kannst - siehe "Alternative: OIDC (externes SSO)" unter "Authentifizierung" weiter
unten für die benötigten Umgebungsvariablen und eine Clerk-Anleitung.

### Für die Entwicklung: aus dem Quellcode ausführen

Nur nötig, wenn du am Code von Notenfuchs arbeitest - für die normale Nutzung nicht
erforderlich. Erfordert Java 17+ (der Maven Wrapper, `./mvnw`, ist im Repo enthalten,
eine separate Maven-Installation ist also nicht nötig).

Nur die Datenbank starten, entkoppelt, damit dieses Terminal frei bleibt:

```bash
docker compose up -d postgres
```

Dann die App im Quarkus-Dev-Modus starten (Live-Reload) - das erzeugt auch tatsächlich
das Schema (Flyway läuft beim Start):

```bash
./mvnw quarkus:dev
```

Standardmäßig verbindet sich das mit `jdbc:postgresql://localhost:5432/notenfuchs`
(Benutzer/Passwort `notenfuchs`), passend zum obigen `postgres`-Service. Überschreibe
`DB_URL`, `DB_USER`, `DB_PASSWORD` als Umgebungsvariablen, wenn du eine andere
Datenbank ansprichst.

> Hinweis: Quarkus kann normalerweise über **Dev Services** automatisch eine
> Wegwerf-Datenbank bereitstellen, wenn gar keine Datenquelle konfiguriert ist.
> Dieses Projekt konfiguriert bewusst eine explizite PostgreSQL-Datenquelle (siehe
> `src/main/resources/application.properties`), damit Dev-Modus und `docker compose`
> dieselbe schema-verwaltete Datenbank nutzen statt einer flüchtigen.

Eine frische Datenbank (noch keine `school_class`-Zeilen) bekommt beim ersten Anlegen
des Schemas durch Flyway automatisch eine Demo-Klasse ("Demo-Klasse 8b") eingespielt -
nichts Zusätzliches nötig. Das passiert nur einmal pro Datenbank: Wenn du die
Demo-Klasse danach löschst, kommt sie nicht zurück (Flyway-Migrationen laufen nicht
erneut).

Möchtest du den kompletten Stack (App + Postgres) mit deinen eigenen lokalen
Änderungen statt dem veröffentlichten Image starten?

```bash
./mvnw package
docker compose up --build
```

Das baut ein JVM-Modus-Container-Image aus `src/main/docker/Dockerfile.jvm`, das
erwartet, dass `./mvnw package` bereits `target/quarkus-app/` erzeugt hat.

### Tests ausführen

```bash
./mvnw test      # Unit-Tests
./mvnw verify    # führt zusätzlich die Browser-End-to-End-Tests aus (benötigt Docker)
```

`GradeServiceTest` ist ein reiner JUnit-5-Unit-Test (kein `@QuarkusTest`, keine
Datenbank), der die Notenberechnungslogik direkt prüft.

`GradeGridE2EIT` (`src/test/java/de/notenfuchs/e2e`) steuert das echte
Noteneingabe-Raster über einen Browser mit [Playwright](https://playwright.dev/),
über die Erweiterung
[quarkus-playwright](https://docs.quarkiverse.io/quarkus-playwright/dev/). Er läuft
als Failsafe-Integrationstest (`./mvnw verify`, nicht `./mvnw test`), da er Docker
benötigt: sowohl der Browser (Playwrights Dev-Services-Container) als auch PostgreSQL
(Testcontainers Dev Services) laufen in Containern, keine lokale
Browser-Installation nötig.

`OwnershipGuardIT` (`src/test/java/de/notenfuchs/security`) ist ebenfalls ein
Failsafe-Integrationstest (benötigt Docker für sein Testcontainers-Postgres, kein
Browser beteiligt), der `SchoolClass`/`Subject`-Zeilen mit unterschiedlichen
`ClassTeacher`/`SubjectTeacher`-Eigentümern anlegt und die Mandantentrennung direkt
gegen `OwnershipGuard` prüft - siehe "Datenzugriff pro Lehrkraft" oben.

### Screenshots im README neu erzeugen

Die Bilder unter "Screenshots" oben sind nicht von Hand aufgenommen, sondern werden
von `ReadmeScreenshotIT` (`src/test/java/de/notenfuchs/e2e`) generiert - es steuert
die echte Oberfläche über einen Browser genau wie die anderen Playwright-ITs und
schreibt dann verlustfreie, metadatenbereinigte PNGs nach `screenshots/`. Er ist vom
Standardlauf `./mvnw verify` ausgeschlossen (siehe `pom.xml`), da es sich um einen
manuellen, bei Bedarf auszuführenden Schritt handelt, nicht um einen Regressionstest -
führe ihn explizit aus, wann immer sich die Oberfläche so weit ändert, dass die
aktuellen Screenshots veraltet sind:

```bash
./mvnw verify -Dit.test=ReadmeScreenshotIT
```

Prüfe danach `git diff --stat screenshots/` und committe nur, wenn sich tatsächlich
etwas geändert hat - ein erneuter Lauf ohne sichtbare UI-Änderung erzeugt
byteidentische Dateien, unabhängig davon, an welchem Tag er läuft.

## Das Notenmodell

- **GradeScale**: definiert eine Notenskala (`min`, `max` und `lowerIsBetter`). Die
  deutsche Schulnotenskala ("DE 1-6", 1 = beste, 6 = schlechteste Note) wird durch die
  initiale Flyway-Migration eingespielt.
- **Subject**: gehört zu einer `SchoolClass`, referenziert eine `GradeScale` und hat
  einen `roundingMode` (`COMMERCIAL` oder `IN_FAVOR_OF_STUDENT`).
- **GradeCategory**: eine gewichtete Kategorie innerhalb eines Fachs (z. B.
  "Schriftlich" 50 %, "Mündlich" 50 %), identifiziert über `weightPercent`.
- **Assessment**: eine einzelne bewertete Leistung innerhalb einer Kategorie (z. B.
  eine Klassenarbeit), mit einem `factor` (Standard 1.0), der steuert, wie stark sie
  innerhalb ihrer Kategorie zählt.
- **Grade**: das numerische Ergebnis (`NUMERIC(4,2)`) eines Schülers für eine
  Leistung.

### Wie Durchschnitte berechnet werden

Für einen gegebenen Schüler und ein Fach:

1. **Kategoriedurchschnitt** = der gewichtete Mittelwert der Noten des Schülers in
   dieser Kategorie, jede Note gewichtet mit dem `factor` ihrer Leistung:
   `summe(wert_i * faktor_i) / summe(faktor_i)`.
2. **Fachdurchschnitt** = die gewichtete Kombination der Kategoriedurchschnitte
   anhand des `weightPercent` jeder Kategorie, normalisiert nur über die Kategorien,
   in denen für diesen Schüler tatsächlich mindestens eine Note vorliegt. Eine leere
   Kategorie (noch keine Noten) wird vollständig ausgeschlossen, statt den
   Durchschnitt nach unten zu ziehen - wenn z. B. "Mündlich" (50 %) noch keine Noten
   hat, ist der Fachdurchschnitt einfach der "Schriftlich"-Durchschnitt, nicht
   verwässert durch eine implizite Null.
3. Der **Rohdurchschnitt** wird für die Anzeige auf 2 Nachkommastellen gerundet
   ausgegeben (die interne Berechnung nutzt durchgehend höhere Präzision,
   `MathContext.DECIMAL64`).
4. Die **Endnote** ist der Rohdurchschnitt, gerundet auf eine ganze Zahl gemäß dem
   `roundingMode` des Fachs:
   - `COMMERCIAL`: kaufmännische Rundung ab 0,5 aufwärts (z. B. 2,50 -> 3), unabhängig
     davon, welche Richtung auf der Skala "besser" ist.
   - `IN_FAVOR_OF_STUDENT`: identisch, außer dass ein exakter halber Wert (x,50) zu
     der ganzen Zahl hin gerundet wird, die für den Schüler besser ist, basierend auf
     dem `lowerIsBetter`-Flag der Skala (z. B. auf der DE-1-6-Skala: 2,50 -> 2, die
     bessere Note).

Diese Logik befindet sich in `GradeService`
(`src/main/java/de/notenfuchs/service/GradeService.java`) als reiner,
abhängigkeitsfreier POJO-Service, der auf einfachen DTOs (`CategoryData`,
`GradeData`) arbeitet - er greift nie auf die Datenbank zu und codiert nie eine
bestimmte Skala (etwa 1-6) fest, ist also vollständig unit-testbar und
skalen-unabhängig.

## Warum Notenwerte `NUMERIC` sind, kein Enum

Notenwerte werden als einfaches `NUMERIC(4,2)` (`BigDecimal` in Java) gespeichert,
nicht als Enum, das an die deutsche 1-6-Skala gebunden ist. Die Skala selbst - ihre
Grenzen und ob niedrigere oder höhere Werte "besser" sind - liegt vollständig in der
`GradeScale`-Entität/Tabelle. Das bedeutet, eine künftige Notenskala (zum Beispiel
eine 0-15-"Punkte"-Skala, wie sie in der deutschen *Oberstufe*/beim IB-Modell üblich
ist) kann später mit einem einfachen `INSERT INTO grade_scale ...` hinzugefügt werden
und erfordert **keine** Schema-Migration oder Codeänderung an der `grade`-Tabelle -
nur eine neue `GradeScale`-Zeile und ein `Subject`, das darauf verweist.

## Authentifizierung

Notenfuchs benötigt genau eine von zwei Konfigurationen, um zu laufen: ein lokales
Passwort (der Standard, superschnelle Weg - nichts sonst einzurichten) oder einen
OIDC-Issuer (für Schulen, die bereits zentrales SSO betreiben). Was tatsächlich
konfiguriert ist, gewinnt - beide sind nie gleichzeitig aktiv (siehe
`de.notenfuchs.security.LocalAuthConfigSource`). In Produktion verweigert die App den
Start, falls **keines** von beidem konfiguriert ist, statt Notendaten
unauthentifiziert auszuliefern (siehe "Fail-fast in Produktion" weiter unten).

### Standard: lokale integrierte Anmeldung

Setze `NOTENFUCHS_PASSWORD` (in `.env`, oder als echte Umgebungsvariable) und sonst
nichts - `docker compose up` (siehe "Option A" oben) ist dann sofort unter
`http://localhost:8080/login` nutzbar. Dies verwendet Quarkus' eigenen eingebetteten
Security-Realm (`quarkus-elytron-security-properties-file`) plus die eingebaute
FORM-Authentifizierung - keine Datenbanktabelle, kein Registrierungsablauf, keine
Passwort-Reset-UI, kein Hashing.

- **Benutzername** ist der feste Wert `lehrer` (Anwendungsfall
  Einzellehrkraft/Self-Hosting - siehe die Designprinzipien in ROADMAP.md). Um einen
  anderen Benutzernamen zu verwenden, ändere das Literal in
  `de.notenfuchs.security.LocalAuthConfigSource` und baue neu.
- **Passwort** ist, was auch immer `NOTENFUCHS_PASSWORD` gesetzt ist, im Klartext in
  der eingebetteten Realm-Konfiguration gespeichert - dasselbe Vertrauensniveau wie
  `DB_PASSWORD`. Es gibt kein Hashing/Rotations-Tooling; behandle die
  Umgebungsvariable selbst als das Geheimnis.
- Die Anmeldung erfolgt unter `/login`; das Abmelden nutzt den "Logout"-Link in der
  Navigation (`/local-logout`), der das Session-Cookie löscht.

### Alternative: OIDC (externes SSO)

Lasse `NOTENFUCHS_PASSWORD` ungesetzt, um stattdessen diesen Modus zu verwenden.
Notenfuchs sichert dann die gesamte Anwendung (alle UI-Seiten und alle
`/api/*`-Endpunkte) mit [OIDC](https://openid.net/developers/how-connect-works/) über
die `quarkus-oidc`-Erweiterung im **Web-App-Modus**: ein standardmäßiger
Authorization-Code-Flow mit einem serverseitigen Session-Cookie, passend für das
serverseitig gerenderte HTML-Frontend (Qute + HTMX) - kein Token-basiertes
SPA-Setup. Er ist provider-unabhängig und funktioniert mit jedem standardkonformen
OIDC-Provider (Clerk, Keycloak, Authentik, Auth0, ...).

#### Benötigte Umgebungsvariablen (Produktion)

| Variable | Beschreibung |
|---|---|
| `OIDC_ISSUER_URL` | Die Issuer-/Discovery-URL deines Providers, z. B. `https://your-app.clerk.accounts.dev` (Clerk) oder `https://idp.example.com/realms/notenfuchs` (Keycloak). Quarkus ruft davon `<issuer>/.well-known/openid-configuration` ab. |
| `OIDC_CLIENT_ID` | Die bei deinem Provider registrierte OIDC-Client-ID. |
| `OIDC_CLIENT_SECRET` | Das vertrauliche Client-Secret für diesen Client. |

Diese werden auf `quarkus.oidc.auth-server-url`, `quarkus.oidc.client-id` und
`quarkus.oidc.credentials.secret` in `application.properties` abgebildet, die sonst
auf nicht funktionsfähige lokale Dev-Platzhalterwerte zurückfallen.

Registriere die folgende Redirect-URI bei deinem Provider (Host an dein Deployment
anpassen): `https://your-domain.example/auth-callback`. Dies wird von
`quarkus.oidc.authentication.redirect-path` gesteuert und ist standardmäßig
`/auth-callback`. Logout ist unter `/logout` verfügbar (RP-initiiertes Logout,
`quarkus.oidc.logout.*`), das danach zurück auf `/` leitet.

Standardmäßig fordert die App die Scopes `openid profile email` an (`openid` wird
automatisch von Quarkus hinzugefügt; `profile` und `email` sind explizit
konfiguriert), damit Name/E-Mail des angemeldeten Nutzers über den
OIDC-UserInfo-Endpunkt verfügbar sind.

#### Pocket ID (mitgelieferter, selbst gehosteter OIDC-Provider)

Siehe "Option B: Mehrbenutzer, Pocket ID SSO" unter "Notenfuchs betreiben" oben für
die vollständige Einrichtung (das `docker-compose.oidc.yml`-Overlay, das Erzeugen
von `POCKET_ID_ENCRYPTION_KEY`, das Anlegen des Admin-Kontos, das Registrieren des
OIDC-Clients und das Ausstellen von Registrierungslinks).

#### Clerk einrichten

1. Lege im Clerk-Dashboard eine Anwendung an (oder nutze eine bestehende) und füge
   unter "OAuth Applications" eine **OAuth-Anwendung** hinzu (Clerks OIDC/OAuth-Client-
   Funktion) - das liefert eine Client-ID und ein Client-Secret und stellt ein
   standardmäßiges OIDC-Discovery-Dokument bereit.
2. Kopiere die dort angezeigte Issuer-URL (typischerweise
   `https://<deine-instanz>.clerk.accounts.dev`, oder deine eigene Domain) in
   `OIDC_ISSUER_URL`.
3. Kopiere Client-ID und Client-Secret in `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET`.
4. Füge die Callback-URL deines Deployments als erlaubte Redirect-URI in den
   Clerk-OAuth-Anwendungseinstellungen hinzu: `https://your-domain.example/auth-callback`.
5. Vor dem Live-Gang gegenprüfen: bestätige, dass Clerks Discovery-Dokument
   (`<issuer>/.well-known/openid-configuration`) erreichbar ist und dass die
   OAuth-Anwendung ein **vertraulicher** (confidential) Client ist (d. h.
   tatsächlich ein Client-Secret hat) - Clerk vermarktet sich primär als
   Auth-Provider mit eigenen SDKs, die generische OIDC/OAuth-Anwendungsunterstützung
   ist eher ein Nebenfeature, prüfe daher die genaue Discovery-URL und
   Scope-Unterstützung (`profile`, `email`) gegen Clerks aktuelle
   OAuth-Dokumentation für dein Konto, statt Keycloak-ähnliche Standardwerte
   anzunehmen.

### Fail-fast in Produktion

`de.notenfuchs.security.AuthConfigurationCheck` beobachtet `StartupEvent` und wirft
nur im Standard-/Produktionsprofil eine Exception (die den Start abbricht), falls
weder `NOTENFUCHS_PASSWORD` noch `OIDC_ISSUER_URL` gesetzt ist - sodass ein
fehlkonfiguriertes Deployment beim Start laut scheitert, statt still die Notendaten
aller Lehrkräfte unauthentifiziert auszuliefern.

### `%dev`-/Test-Bypass

`quarkus.oidc.tenant-enabled` ist für die Profile `dev` und `test` auf `false`
gesetzt (`%dev.quarkus.oidc.tenant-enabled=false`,
`%test.quarkus.oidc.tenant-enabled=false`; zu beachten: das ist `tenant-enabled`,
nicht der buildzeitige `enabled`-Schalter - letzterer würde die CDI-Beans der
OIDC-Erweiterung komplett entfernen und damit `CurrentUser`s bedingungslose
`@Inject`-Felder brechen), und die pauschale `authenticated`-HTTP-Berechtigungs-
richtlinie wird für dieselben Profile auf `permit` gelockert. Das bedeutet, sowohl
`./mvnw quarkus:dev` als auch `./mvnw test` laufen ohne einen echten
Identity-Provider (oder ein lokal konfiguriertes Passwort) und ohne Login - genau
wie vor Einführung der Authentifizierung. Dieser Bypass ist im
Standard-/Produktionsprofil **nicht** aktiv; verlass dich außerhalb der lokalen
Entwicklung nicht darauf.

### Nutzeridentität im Code

`de.notenfuchs.security.CurrentUser` (Request-scoped Bean) stellt das
OIDC-Subject, die E-Mail und den Anzeigenamen des angemeldeten Nutzers für die
Verwendung in REST-Resources oder Qute-Templates bereit - siehe den Javadoc dieser
Klasse für Details. `CurrentUser.effectiveSubject()` ist der Wert, der tatsächlich
für die Zuordnung der Daten pro Lehrkraft verwendet wird (siehe unten).

### Datenzugriff pro Lehrkraft

Authentifizierung (oben) beweist, *wer* angemeldet ist; die Zugriffskontrolle ist
eine separate Schicht darüber, die entscheidet, welche Klassen/Fächer eine Lehrkraft
sehen und bearbeiten kann, mit drei Stufen über zwei Entitäten - siehe den Abschnitt
"Authorization" in `CLAUDE.md` für das vollständige Modell; die Kurzfassung:

- `ClassTeacher` ordnet eine Lehrkraft einer `SchoolClass` mit einer von zwei Rollen
  zu: `ADMIN` (Schülerliste lesen/schreiben, klassenweite Einstellungen, Verwaltung
  der Admins und der Fachlehrer-Stufe selbst, Löschen jedes Fachs, sowie eine
  schreibgeschützte klassenweite Notenübersicht) oder `FACHLEHRER` (klassenweit:
  kann ein neues Fach anlegen und Fächer löschen/verwalten, die sie/er selbst
  unterrichtet, aber die Klasse nicht administrieren). `SubjectTeacher` markiert,
  wer ein bestimmtes `Subject` unterrichtet (schaltet den gesamten
  Leistungs-Zugriff frei - Kategorien, Leistungen, Noten, Umbenennen - für genau
  dieses Fach, unabhängig von der `ClassTeacher`-Rolle, und ohne Admin-Override:
  das Teilen eines Fachs mit einer Kollegin/einem Kollegen bleibt ausschließlich
  Selbstbedienung durch, wer es aktuell unterrichtet). Einfacher klassenweiter
  Zugriff (Schülerliste lesen, Fächerliste, Verhaltensnoten) ist **abgeleitet**:
  eine Lehrkraft hat ihn, wenn sie eine `ClassTeacher`-Zeile mit einer der beiden
  Rollen hat, oder mindestens eines der Fächer der Klasse unterrichtet.
  `GradeScale` ist gemeinsame Referenzdaten, die niemandem gehören.
- `de.notenfuchs.security.OwnershipGuard` ist die einzige Stelle, an der dies
  durchgesetzt wird. Jeder REST-/Web-Endpunkt, der eine Entität per ID liest oder
  schreibt, löst sie über eine ihrer `require*`-Methoden auf
  (`requireClassAccess`/`requireClassTeacher`/`requireClassAdmin`,
  `requireClassAccessSubject`/`requireTeachesSubject`/`requireCanDeleteSubject`
  usw.); eine fremde Klasse/ein fremdes Fach/ein fremder Schüler usw. und eine
  unbekannte ID liefern beide schlicht **404**, bewusst nicht unterscheidbar,
  damit eine Lehrkraft "existiert nicht" nicht von "gehört dir nicht"
  unterscheiden kann. Listen-Endpunkte (z. B. `GET /api/school-classes`) sind auf
  die Klassen gefiltert, auf die die aktuelle Lehrkraft Zugriff hat, statt die
  Daten aller zurückzugeben.
- In `%dev`/`%test`, wo OIDC deaktiviert ist (siehe oben), fällt
  `CurrentUser.effectiveSubject()` auf ein festes `"dev-user"`-Subject zurück,
  sodass die Zugriffskontrolle auch lokal und in Tests ohne echten Login
  funktioniert.
- Die Detailseite einer Klasse hat einen Abschnitt "Lehrkräfte" (nur für Admins)
  zum Hinzufügen/Entfernen von `ClassTeacher`-Zeilen beider Rollen, mit Auswahl aus
  einem Verzeichnis aller Lehrkräfte, die mindestens eine authentifizierte Anfrage
  an diese Instanz gestellt haben (`de.notenfuchs.domain.Teacher`, aktuell
  gehalten von `de.notenfuchs.security.TeacherDirectoryRecorder` - siehe den
  Abschnitt "Class & subject access UI" in `CLAUDE.md` für Details), sowie einen
  Link "Notenübersicht" (nur für Admins), der die Endnote jedes Schülers in jedem
  Fach schreibgeschützt anzeigt. Die Detailseite eines Fachs hat einen analogen
  Abschnitt "Lehrkräfte", um ein Fach mit einer Kollegin/einem Kollegen zu teilen -
  Selbstbedienung, nur daran gebunden, dass man das Fach unterrichtet, ohne
  Admin-Override, anders als der klassenweite Abschnitt oben.

## Eine kostenlose Demo-Instanz bereitstellen

Damit Interessierte Notenfuchs ausprobieren können, bevor sie sich für den echten
Self-Hosting-Betrieb entscheiden: eine öffentlich erreichbare Instanz auf
Free-Tier-Hosting, die jede Nacht aus dem aktuellen `master` neu deployed und auf
einen festen Demo-Datensatz zurückgesetzt wird. Zwei Bausteine, verbunden über
bereits im Repo vorhandene Dateien:

- **[Render](https://render.com)** betreibt die App selbst, gebaut aus
  `src/main/docker/Dockerfile.render` (ein mehrstufiges Dockerfile, das den
  Maven-Build aus dem Quellcode ausführt - anders als `Dockerfile.jvm`, das
  erwartet, dass `./mvnw package` bereits gelaufen ist) über `render.yaml`
  (Render-"Blueprint" - Render erkennt diese Datei automatisch).
- **[Neon](https://neon.tech)** stellt das kostenlose Postgres bereit - Renders
  eigener Free-Tier hat keine dauerhafte kostenlose Postgres-Option.

### Einmalige Einrichtung

1. Ein kostenloses Neon-Projekt anlegen und dessen Verbindungsdaten notieren (Host,
   Datenbank, Nutzer, Passwort).
2. In Render ein neues **Blueprint** anlegen, das auf dieses Repo zeigt - es
   übernimmt `render.yaml` automatisch.
3. In den Umgebungseinstellungen des Render-Service Folgendes setzen:

   | Variable | Wert |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
   | `DB_USER` | Dein Neon-Rollenname |
   | `DB_PASSWORD` | Dein Neon-Rollenpasswort |
   | `NOTENFUCHS_PASSWORD` | Ein Wegwerf-Passwort, das gefahrlos neben dem Demo-Link veröffentlicht werden kann - diese Instanz enthält ohnehin nur Demodaten, die nachts gelöscht werden (siehe unten). |

4. Einen **Deploy Hook** in den Einstellungen des Render-Service anlegen und dessen
   URL kopieren.
5. Die Service-ID (`srv-...`, in der URL des Service) notieren und einen
   Render-API-Key erzeugen (Kontoeinstellungen).
6. Vier Secrets in diesem GitHub-Repo hinzufügen (Settings → Secrets and variables
   → Actions): `RENDER_DEPLOY_HOOK_URL`, `RENDER_API_KEY`, `RENDER_SERVICE_ID` und
   `DEMO_DATABASE_URL` (der Neon-Verbindungsstring, direkt/ungepoolt,
   `postgresql://user:password@host/db?sslmode=require`).

### Was jede Nacht passiert

`.github/workflows/demo-nightly-redeploy.yml` läuft nach einem Cron-Zeitplan
(03:00 UTC) und:

1. Löst den Render-Deploy-Hook aus, der den aktuellen `master`-Commit neu deployed.
2. Fragt die Render-API ab, bis das Deployment `live` meldet (statt eines festen
   Sleep - Free-Tier-Builds können einige Minuten dauern).
3. Führt `demo/seed-reset.sql` direkt gegen die Neon-Datenbank aus, löscht dabei
   jede lehrkraft-eigene Tabelle (`grade_scale` - gemeinsame Referenzdaten - bleibt
   unberührt) und lädt eine feste Demo-Klasse (`Demo-Klasse 8b`, im Besitz des
   festen lokalen Auth-Nutzers `lehrer` - siehe "Standard: lokale integrierte
   Anmeldung" oben) mit ein paar Schülern und Noten neu ein. Das ist getrennt von
   der einmaligen Flyway-Seed-Einspielung bei einer frischen
   Self-Hosting-Installation (siehe "Für die Entwicklung" oben) - dieses Skript
   läuft nächtlich, außerhalb von Flyway.

Manuell über den Actions-Tab auslösen (`workflow_dispatch`), um bei Bedarf neu zu
deployen/zurückzusetzen, statt auf den nächtlichen Lauf zu warten.

### Einschränkungen

- Renders kostenlose Web-Services fahren nach ca. 15 Minuten Inaktivität herunter;
  die erste Anfrage danach weckt sie wieder auf (30-60 s Kaltstart). Für eine
  "mal ausprobieren"-Demo in Ordnung, nicht für irgendetwas latenzempfindliches.
- Die Free-Tier-Bedingungen beider Plattformen ändern sich mit der Zeit - prüfe
  Renders und Neons aktuelle Grenzen, bevor du dich langfristig darauf verlässt.
- Das ist eine Wegwerf-Demo, keine Vorlage für ein echtes Deployment: das
  veröffentlichte `NOTENFUCHS_PASSWORD` und das nächtliche Zurücksetzen sind hier
  beide beabsichtigt und für eine Instanz mit echten Schülerdaten falsch.

## REST-API

Alle Endpunkte liegen unter `/api` und sind auf die angemeldete Lehrkraft
beschränkt (siehe "Datenzugriff pro Lehrkraft" oben) - Listen-Endpunkte liefern nur
die Daten dieser Lehrkraft, und eine fremde oder unbekannte ID liefert 404:

- `GET/POST /api/school-classes`, `GET/PUT/DELETE /api/school-classes/{id}`
- `GET/POST /api/students`, `GET/PUT/DELETE /api/students/{id}` (Liste filtern mit `?schoolClassId=`)
- `GET/POST /api/subjects`, `GET/PUT/DELETE /api/subjects/{id}` (Liste filtern mit `?schoolClassId=`)
- `GET/POST /api/grade-categories`, `GET/PUT/DELETE /api/grade-categories/{id}` (filtern mit `?subjectId=`)
- `GET/POST /api/assessments`, `GET/PUT/DELETE /api/assessments/{id}` (filtern mit `?categoryId=`)
- `GET/POST /api/grades`, `GET/PUT/DELETE /api/grades/{id}` (filtern mit `?studentId=` / `?assessmentId=`)
- `GET/POST /api/behavior-grades`, `GET/PUT/DELETE /api/behavior-grades/{id}` (filtern mit
  `?studentId=` / `?subjectId=`) - Verhaltensnoten, unabhängig von der akademischen Note oben
- `GET /api/grade-scales`, `GET /api/grade-scales/{id}` (schreibgeschützt, für alle Lehrkräfte gemeinsam)
- `GET /api/school-classes/{classId}/averages` - berechneter Rohdurchschnitt + Endnote für
  jede Kombination aus Schüler x Fach in dieser Klasse

## Web-Frontend

Notenfuchs liefert ein serverseitig gerendertes HTML-Frontend (Quarkus-Qute-
Templates + HTMX für partielle Updates, plus eine kleine Menge Vanilla-JS für die
tastaturgesteuerte Tabellen-Navigation des Noteneingabe-Rasters) - kein React/SPA,
kein Node-Build-Schritt.

- `/` leitet auf `/classes` weiter
- `/classes` - Klassen auflisten/anlegen/löschen
- `/classes/{id}` - die Fächer und Schüler einer Klasse verwalten
- `/subjects/{id}` - die Notenkategorien und Leistungen eines Fachs verwalten
- `/subjects/{id}/grid` - das Noteneingabe-Raster: Schüler als Zeilen, Leistungen als
  Spalten (gruppiert nach Kategorie), jede Zelle speichert bei Blur/Verlassen
  automatisch über einen kleinen `fetch()`-Aufruf, mit einer live berechneten
  Durchschnittsspalte pro Schüler (`GradeService`)
- `/subjects/{id}/grid/export` - lädt dasselbe Raster als `.xlsx`-Arbeitsmappe
  herunter (Apache POI), für Lehrkräfte, die die Noten außerhalb der App haben
  möchten
- `/classes/{id}/roster/export` - lädt die Schülernamen dieser Klasse als CSV
  herunter
- `/classes/{id}/roster/import/preview` und `/classes/{id}/roster/import` - eine
  CSV mit Schülernamen hochladen, eine Vorschau anzeigen, welche Zeilen neu sind
  bzw. bereits existieren (per exaktem Namensabgleich), und dann bestätigen, um die
  neuen Schüler anzulegen
- `/classes/{id}/behavior-grid` - das Verhaltensnoten-Raster: Schüler als Zeilen,
  jedes Fach der Klasse als Spalten, zur Eingabe einer Verhaltensnote pro Schüler
  und Fach. Unabhängig von `GradeService`/dem akademischen Durchschnitt - eine
  eigene Kennzahl fürs Halbjahres-/Endjahreszeugnis. Zeigt einen live berechneten
  Durchschnitt pro Fach (eigene Skala, gerundete Endnote) sowie einen Durchschnitt
  pro Schüler über alle Fächer (nur roh, da Fächer unterschiedliche Skalen nutzen
  können), hervorgehoben, wenn nahe an einer Rundungsgrenze zur nächsten ganzen
  Note (z. B. 2,4-2,6, nahe 2,5)

### Roster-CSV-Format

Ein "Roster" ist einfach die Liste der Schülernamen einer Klasse, einer pro Zeile
unter einer `Name`-Kopfzeile. Wird von `CsvRosterService` (`de.notenfuchs.service`)
behandelt, einem reinen, DB-freien Service - wie `GradeService` - daher ohne
Datenbank unit-testbar.

- **Export** schreibt immer UTF-8 mit BOM und eine **semikolon**-getrennte
  `Name`-Spalte, damit Excel mit deutscher Locale Umlaute out of the box korrekt
  öffnet, ohne manuelle Nachfrage zur Kodierung.
- **Import** ist tolerant gegenüber dem, was echte Exporte aus deutschem Excel
  tatsächlich aussehen: er erkennt das Trennzeichen (`;` vs. `,`) aus der
  Kopfzeile, dekodiert UTF-8 (mit oder ohne BOM) und fällt auf Windows-1252
  zurück, falls die Bytes kein gültiges UTF-8 sind, und akzeptiert sowohl CRLF-
  als auch LF-Zeilenenden. Eine `Name`-Kopfzeile (Groß-/Kleinschreibung egal) wird
  erkannt und, falls vorhanden, entfernt. Eine Kopfzeile mit separaten
  `Vorname`/`Nachname`-Spalten (Groß-/Kleinschreibung egal, beliebige Position,
  andere Spalten wie `Alter`/`Klasse`/`Geburtsdatum` werden ignoriert) wird
  ebenfalls erkannt, und die beiden Spalten werden mit einem Leerzeichen zum
  vollen Namen zusammengefügt - das Format, das viele Schulverwaltungssysteme
  exportieren. Ohne eine dieser erkennbaren Kopfzeilen wird jede Zeile als Name
  behandelt. Leerzeilen werden übersprungen, Namen werden getrimmt.
- Der Import ist ein zweistufiger, zustandsloser Ablauf: das Hochladen einer CSV
  rendert eine **Vorschau**-Seite, die jede Zeile als NEU oder DUPLIKAT markiert
  (gegen die bestehenden Schüler der Klasse, exakter Namensabgleich), bevor
  irgendetwas in die Datenbank geschrieben wird. Das Bestätigungsformular der
  Vorschau führt die geparsten Namen als versteckte Eingabefelder zurück, statt
  sich auf eine serverseitige Session zu verlassen, sodass die
  Bestätigungsanfrage in sich abgeschlossen ist. Das Bestätigen legt pro neuem
  Namen einen `Student` an und überspringt Duplikate (auch Duplikate innerhalb
  der hochgeladenen Datei selbst).

**HTMX wird selbst gehostet**, nicht von einem CDN geladen. `htmx.min.js`
(v1.9.12) liegt eingebunden unter
`src/main/resources/META-INF/resources/static/js/htmx.min.js` und wird über den
`<script>`-Tag in `templates/base.html` unter `/static/js/htmx.min.js`
ausgeliefert. Für ein Upgrade diese Datei durch ein neueres `htmx.min.js` von
https://htmx.org ersetzen und die hier vermerkte Version aktualisieren.

## Lizenz

MIT, siehe [LICENSE](LICENSE).
