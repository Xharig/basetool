# UC-46 — Austausch: Device-Login, Sync über das Gateway, Rücknahme & Trennen

|                |                                                                                                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-46                                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                                        |
| **Testklasse** | [`ExchangeRoundTripE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ExchangeRoundTripE2eTest.java) · Registerpflege: [`AdminExchangeClientsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminExchangeClientsE2eTest.java) |
| **Spec**       | [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-005/-006, REQ-XCH-029, REQ-XCH-032) · [ADR-0225](../adr/0225-the-e2e-stack-runs-the-sandbox-keycloak-and-the-ingest-gateway.md) |

## Akteure

- **Drittanbieter-Client** `e2e-exchange-client` — öffentlicher Device-Grant-Client mit Zustimmung und DPoP-gebundenen Tokens, aus der Vorlage des Produktions-Provisioners im erzeugten E2E-Realm. Im Test spielt ihn `ExchangeTestClient`: ein P-256-Schlüssel pro Installation, DPoP-Proofs mit `ath` und `nonce`, Aufrufe an das Gateway auf `https://localhost:11262`.
- **Mitglied** `test-exchange` — ein eigener Realm-Nutzer, den sonst kein Test verwendet, mit den Standardrollen (für das Offline-Token) und `KRT Member`; über den Seeder in der IRIDIUM-Staffel.
- **Admin** `test-admin` — registriert den Client und schaltet den Austausch ein.

## Vorbedingungen

Nur im ephemeren Modus (`assumeTrue(STACK.managesStack())`), weil der Test das Gateway, die Admin-API und die Datenbank des Stacks braucht:

- `exchange-e2e-seed.sql` legt per JDBC eine Blaupause (`BP_CRAFT_E2EM_EXCHANGE_RIFLE_01`, „E2E Exchange Rifle") und ein Material („E2E Exchange Metal", SCU) an; der Lagerort „E2E Refinery Hub" kommt aus `uex-catalog-seed.sql`.
- Der Admin registriert `e2e-exchange-client` mit Verbinden, Blaupausen und Lager lesen und schreiben und schaltet den globalen Schalter ein, falls er aus ist.

## Hauptablauf

1. **Device-Login:** Der Client startet den Device-Grant mit `offline_access` und den Austausch-Scopes. Im Browser öffnet das Mitglied die Verifizierungsseite von Keycloak (`krt-theme`), meldet sich an und stimmt zu.
2. **Token:** Der Client fragt den Token-Endpunkt mit DPoP-Proof ab, bis das Token kommt; es ist an den Schlüssel gebunden (`cnf.jkt`) und nennt die Gateway-Audience `basetool-ingest`.
3. **Sync:** Der Client wartet, bis das Gateway ihn über den Registry-Spiegel kennt, benennt seine Installation, löst Blaupause und Material über `catalog/resolve` auf und schreibt je eine Änderung: Blaupause hinzufügen, Lagerbestand von 0 auf 5 SCU.
4. **Verbundene Anwendungen:** Das Mitglied öffnet `/connected-apps` und sieht den Client mit seiner Installation samt Bezeichnung und „Neu"-Markierung sowie beide Änderungen unter „Letzte Änderungen".
5. **Rücknahme:** „Änderungen zurücknehmen" für die letzte Stunde.
6. **Trennen:** „Anwendung trennen" und bestätigen.
7. **Nächster Aufruf:** Der Client ruft das Gateway erneut auf.

## Erwartetes Ergebnis

- Beide Änderungen werden angewandt und sind danach über das Gateway lesbar.
- Nach der Rücknahme erscheint das Ergebnisfeld, jede Aktivitätszeile ist als zurückgenommen markiert, die Blaupause fehlt wieder und der Lagerbestand ist ausgebucht.
- Nach dem Trennen verschwindet der Client von der Seite — Rücknahme und Trennen ohne Neuladen.
- Das Gateway weist den Client danach mit `401 CLIENT_REVOKED` ab.

## Sonderfälle & Lehren

- **Keycloak unter zwei Namen.** Der Test-Client spricht Keycloak als `http://localhost:18080` an; das DPoP-`htu` am Token-Endpunkt ist diese URL, nicht der konfigurierte Hostname. Der Browser öffnet die Verifizierungsseite unter `host.docker.internal:18080` wie jeder andere Login der Suite (Resolver-Regel bzw. Hosts-Eintrag).
- **Der Spiegel braucht einen Moment.** Registrierung und Trennen erreichen das Gateway über den Redis-Spiegel des Backends; der Test fragt bis zu 60 s nach.
- **Wiederholbar auf demselben Stack.** Ist der Client schon registriert, bleibt er es; ein gesperrter wird wieder aktiviert.
