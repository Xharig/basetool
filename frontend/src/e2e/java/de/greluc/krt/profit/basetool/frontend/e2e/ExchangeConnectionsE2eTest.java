/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.ADMIN;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.ADMIN_PASSWORD;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.CLIENT_ID;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.SCOPES;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.assertOk;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.awaitAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The exchange's connection controls end to end (REQ-XCH-008, REQ-XCH-031, REQ-XCH-032,
 * REQ-XCH-003): revoking one installation leaves the other working, a client disconnected as a
 * whole connects again at once, the account check answers without the handle, and an admin's
 * suspension and reactivation reach the gateway.
 */
@Tag("e2e")
class ExchangeConnectionsE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** The member who connects; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange-2";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-2-pw";

  /** The RSI handle the account check compares against. */
  private static final String HANDLE = "E2E_Exchange_Two";

  /** A read route every connected installation may call. */
  private static final String READ = "/exchange/v1/me/blueprints";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /** Seeds the catalogue rows, homes the member, registers the client and switches it on. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "the flows need the local stack's gateway and admin API");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    seeder = new BackendSeeder();
    ExchangeE2eSupport.prepare(seeder, MEMBER, MEMBER_PASSWORD);
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Connects two installations, disconnects one on „Verbundene Anwendungen" in place, and checks
   * the gateway refuses that installation even after a token refresh while the other keeps working.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void revokingOneInstallationLeavesTheOtherWorking() throws Exception {
    String run = runId();
    String revokedLabel = "E2E revoked " + run;
    String keptLabel = "E2E kept " + run;
    ExchangeTestClient revoked = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(revoked, revokedLabel);
    ExchangeTestClient kept = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(kept, keptLabel);

    onConnectedApps(
        "exchange-installation-revoke",
        page -> {
          Locator app = page.locator("section.ca-app[data-client-id='" + CLIENT_ID + "']");
          Locator rows = app.locator("table.ca-installations tbody tr[data-installation-id]");
          Locator revokedRow = rows.filter(new Locator.FilterOptions().setHasText(revokedLabel));
          Locator keptRow = rows.filter(new Locator.FilterOptions().setHasText(keptLabel));
          assertThat(revokedRow).hasCount(1);
          assertThat(keptRow).hasCount(1);
          revokedRow.locator("[data-ca-disconnect-installation]").click();
          page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
          assertThat(revokedRow).hasCount(0);
          assertThat(keptRow).hasCount(1);
        });

    ExchangeTestClient.Answer refused = revoked.call("GET", READ, null);
    assertEquals(401, refused.status(), "the revoked installation is refused: " + refused);
    assertEquals("INSTALLATION_REVOKED", refused.code(), "refused as revoked: " + refused);
    revoked.refresh();
    ExchangeTestClient.Answer afterRefresh = revoked.call("GET", READ, null);
    assertEquals(
        "INSTALLATION_REVOKED",
        afterRefresh.code(),
        "a refreshed token of the revoked key is refused too: " + afterRefresh);
    assertOk(kept.call("GET", READ, null));
  }

  /**
   * Disconnects the whole client on „Verbundene Anwendungen", checks the gateway refuses it, and
   * connects a new installation whose very first call is answered.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void aNewConnectionAfterAWholeClientDisconnectWorksAtOnce() throws Exception {
    ExchangeTestClient before = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(before, "E2E before " + runId());

    onConnectedApps(
        "exchange-client-disconnect",
        page -> {
          Locator app = page.locator("section.ca-app[data-client-id='" + CLIENT_ID + "']");
          assertThat(app).hasCount(1);
          app.locator("[data-ca-disconnect-client]").click();
          page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
          assertThat(app).hasCount(0);
        });

    ExchangeTestClient.Answer refused = before.call("GET", READ, null);
    assertEquals(401, refused.status(), "the disconnected client is refused: " + refused);
    assertEquals("CLIENT_REVOKED", refused.code(), "refused as revoked: " + refused);

    ExchangeTestClient after = new ExchangeTestClient(CLIENT_ID);
    ExchangeTestClient.DeviceLogin login = after.startDeviceLogin(SCOPES);
    ExchangeE2eSupport.approveOnTheDevicePage(browser, login, MEMBER, MEMBER_PASSWORD);
    after.awaitToken(login);
    assertOk(after.call("GET", READ, null));
  }

  /**
   * Asks the account check before and after the member stores a handle, and checks it answers
   * {@code unknown}, a case-insensitive {@code match} and a {@code mismatch} without ever returning
   * the stored handle.
   *
   * @throws Exception if a call to Keycloak, the backend or the gateway cannot be sent
   */
  @Test
  void theAccountCheckAnswersUnknownMatchAndMismatchWithoutTheHandle() throws Exception {
    storeHandle(null);
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);

    assertEquals("unknown", accountCheck(client, HANDLE));
    storeHandle(HANDLE);
    assertEquals("match", accountCheck(client, HANDLE.toLowerCase(Locale.ROOT)));
    assertEquals("mismatch", accountCheck(client, "Someone_Else_E2E"));
  }

  /**
   * Suspends the client on the admin registry page, waits until a call is refused and checks the
   * first refusal is {@code 403 CLIENT_SUSPENDED} — whether the gateway's gate or, while its
   * registry cache still holds the client active, the backend's gate refused it — then reactivates
   * it there and waits until the gateway answers again, every refusal meanwhile being the same.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void anAdminSuspensionAndReactivationReachTheGateway() throws Exception {
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    String registryId = ExchangeE2eSupport.registryEntry(seeder).get("id").getAsString();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN, ADMIN_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/admin/exchange-clients");
        page.waitForLoadState();
        Locator row = page.locator("#xc-table tbody tr[data-client-id='" + registryId + "']");
        assertThat(row).hasAttribute("data-status", "ACTIVE");
        row.locator("[data-xc-suspend]").click();
        page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
        assertThat(row).hasAttribute("data-status", "SUSPENDED");

        ExchangeTestClient.Answer refused =
            awaitAnswer(client, "GET", READ, answer -> answer.status() != 200);
        assertEquals(403, refused.status(), "the first refusal is the suspension: " + refused);
        assertEquals("CLIENT_SUSPENDED", refused.code(), "refused as suspended: " + refused);

        row.locator("[data-xc-activate]").click();
        assertThat(row).hasAttribute("data-status", "ACTIVE");
        awaitAnswer(client, "GET", READ, ExchangeConnectionsE2eTest::answeredOrStillSuspended);
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-admin-suspension");
        throw failure;
      } finally {
        ExchangeE2eSupport.registerClient(seeder);
      }
    }
  }

  /**
   * Tells whether a call after the reactivation is answered, and fails on any refusal other than
   * the suspension the gateway's registry cache or the backend may still report (REQ-XCH-003).
   *
   * @param answer the gateway's answer
   * @return {@code true} once the call is answered
   */
  private static boolean answeredOrStillSuspended(ExchangeTestClient.Answer answer) {
    if (answer.status() != 200) {
      assertEquals(403, answer.status(), "still refused as suspended: " + answer);
      assertEquals("CLIENT_SUSPENDED", answer.code(), "still refused as suspended: " + answer);
    }
    return answer.status() == 200;
  }

  /**
   * Opens „Verbundene Anwendungen" as the member, runs the steps and checks the page was never
   * reloaded.
   *
   * @param label the prefix of the failure dump
   * @param steps the steps on the page
   */
  private static void onConnectedApps(String label, Consumer<Page> steps) {
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/connected-apps");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        steps.accept(page);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the disconnect updates the page in place");
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }

  /**
   * Stores or clears the member's RSI handle through the profile API.
   *
   * @param handle the handle, or {@code null} to clear it
   */
  private static void storeHandle(String handle) {
    JsonObject current =
        JsonParser.parseString(
                seeder.getBody(MEMBER, MEMBER_PASSWORD, "/api/v1/users/me/rsi-handle"))
            .getAsJsonObject();
    JsonObject request = new JsonObject();
    request.addProperty("rsiHandle", handle);
    request.addProperty("version", current.get("version").getAsLong());
    assertEquals(
        200,
        seeder.putForStatus(
            MEMBER, MEMBER_PASSWORD, "/api/v1/users/me/rsi-handle", request.toString()),
        "the profile stores the handle");
  }

  /**
   * Asks the gateway's account check and returns its result, asserting the stored handle is not in
   * the answer.
   *
   * @param client the connected client
   * @param handle the handle the client read from the game
   * @return {@code match}, {@code mismatch} or {@code unknown}
   * @throws Exception if the call cannot be sent
   */
  private static String accountCheck(ExchangeTestClient client, String handle) throws Exception {
    JsonObject request = new JsonObject();
    request.addProperty("handle", handle);
    ExchangeTestClient.Answer answer =
        client.call("POST", "/exchange/v1/me/account-check", request);
    assertEquals(200, answer.status(), "the account check answers: " + answer);
    assertEquals(
        Set.of("result"), answer.body().keySet(), "the answer carries the result only: " + answer);
    return answer.body().get("result").getAsString();
  }

  /**
   * Returns a short random marker that tells this run's installations apart.
   *
   * @return eight lower-case hex digits
   */
  private static String runId() {
    return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
  }
}
