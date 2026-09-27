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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The exchange round trip on the E2E stack (REQ-XCH-029, REQ-XCH-032, ADR-0225): a third-party
 * client signs the member in through the device grant, syncs a blueprint and a stock lot through
 * the ingest gateway with a DPoP-bound token, and the member then sees the client on „Verbundene
 * Anwendungen", undoes its changes, and disconnects it, after which the gateway refuses the client.
 */
@Tag("e2e")
class ExchangeRoundTripE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** The admin who registers the client and switches the exchange on. */
  private static final String ADMIN = "test-admin";

  /** The admin's throwaway password from {@code realm-export.e2e.json}. */
  private static final String ADMIN_PASSWORD = "test-admin-pw";

  /** The member who connects the client; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-pw";

  /** The E2E realm's third-party client, public, device grant, consent and DPoP-bound tokens. */
  private static final String CLIENT_ID = "e2e-exchange-client";

  /** The registry display name, which the member page shows. */
  private static final String CLIENT_NAME = "E2E Exchange Client";

  /** The label the client gives its installation. */
  private static final String LABEL = "E2E round trip";

  /** The scopes the device login requests. */
  private static final String SCOPES =
      "offline_access exchange.connect exchange.blueprints.read exchange.blueprints.write"
          + " exchange.stock.read exchange.stock.write";

  /** The capabilities the registry grants the client. */
  private static final List<String> CAPABILITIES =
      List.of(
          "exchange.connect",
          "exchange.blueprints.read",
          "exchange.blueprints.write",
          "exchange.stock.read",
          "exchange.stock.write");

  /** The canonical IRIDIUM Squadron the member is homed in. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /** The seeded blueprint's record key, see {@code exchange-e2e-seed.sql}. */
  private static final String BLUEPRINT_RECORD = "BP_CRAFT_E2EM_EXCHANGE_RIFLE_01";

  /** The seeded blueprint's product name. */
  private static final String BLUEPRINT_NAME = "E2E Exchange Rifle";

  /** The seeded material the stock lot holds. */
  private static final String MATERIAL_NAME = "E2E Exchange Metal";

  /** The Lager location of the stock lot, from {@code uex-catalog-seed.sql}. */
  private static final String LOCATION_NAME = "E2E Refinery Hub";

  /** How long the gateway may take to see a registry change or a disconnect. */
  private static final Duration MIRROR_WAIT = Duration.ofSeconds(60);

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Seeds the catalogue rows, homes the member, registers the client and switches the exchange on.
   */
  @BeforeAll
  static void setUp() {
    assumeTrue(
        STACK.managesStack(), "the round trip needs the local stack's gateway and admin API");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    BackendSeeder seeder = new BackendSeeder();
    seeder.seedSql("/exchange-e2e-seed.sql");
    String memberId = seeder.getUserId(MEMBER, MEMBER_PASSWORD);
    seeder.assignStaffelMembership(
        ADMIN, ADMIN_PASSWORD, memberId, IRIDIUM_SQUADRON_ID, false, false);
    registerClient(seeder);
    switchExchangeOn(seeder);
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
   * Connects, syncs a blueprint and a stock lot, then undoes and disconnects on the member page,
   * without a reload, and checks the gateway refuses the client afterwards.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void aClientSyncsAndTheMemberUndoesAndDisconnectsIt() throws Exception {
    ExchangeTestClient client = new ExchangeTestClient(CLIENT_ID);
    ExchangeTestClient.DeviceLogin login = client.startDeviceLogin(SCOPES);
    approveOnTheDevicePage(login.verificationUriComplete());
    client.awaitToken(login);

    JsonObject claims = client.accessTokenClaims();
    assertEquals(
        client.thumbprint(),
        claims.getAsJsonObject("cnf").get("jkt").getAsString(),
        "the token is bound to the installation key");
    assertTrue(
        claims.get("aud").toString().contains(E2eStackExtension.INGEST_AUDIENCE),
        "the token names the gateway audience: " + claims.get("aud"));

    awaitAnswer(client, "GET", "/exchange/v1", a -> a.status() == 200);
    JsonObject label = new JsonObject();
    label.addProperty("label", LABEL);
    assertOk(client.call("POST", "/exchange/v1/me/installation", label));

    JsonObject blueprintRef = new JsonObject();
    blueprintRef.addProperty("scRecord", BLUEPRINT_RECORD);
    JsonObject blueprint = client.resolve("BLUEPRINT", blueprintRef);
    JsonObject materialRef = new JsonObject();
    materialRef.addProperty("name", MATERIAL_NAME);
    JsonObject material = client.resolve("MATERIAL", materialRef);

    assertApplied(
        client.call("POST", "/exchange/v1/me/blueprints/changes", addBlueprint(blueprint)));
    assertApplied(client.call("POST", "/exchange/v1/me/stock/changes", bookIn(material)));
    assertTrue(holdsBlueprint(client), "the synced blueprint is in the member's set");
    assertTrue(holdsStock(client, material), "the synced lot is in the member's stock");

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

        Locator app = page.locator("section.ca-app[data-client-id='" + CLIENT_ID + "']");
        assertThat(app.locator("h2")).hasText(CLIENT_NAME);
        Locator installation = app.locator("table.ca-installations tbody tr[data-installation-id]");
        assertThat(installation).hasCount(1);
        assertThat(installation).containsText(LABEL);
        assertThat(installation.locator("[data-ca-unseen]")).isVisible();
        Locator activity = app.locator("[data-testid='ca-activity'] tbody tr");
        assertThat(app.locator("[data-testid='ca-activity']")).containsText(BLUEPRINT_NAME);
        assertThat(app.locator("[data-testid='ca-activity']")).containsText(MATERIAL_NAME);
        int written = activity.count();
        assertTrue(written >= 2, "the activity lists both writes, found " + written);

        app.locator("[data-ca-undo]").click();
        page.locator("#ca-undo-span").selectOption("1");
        page.locator("[data-testid='ca-undo-submit']").click();
        assertThat(page.locator("[data-testid='ca-undo-result']")).isVisible();
        assertThat(app.locator("[data-testid='ca-activity'] tbody tr.ca-undone")).hasCount(written);
        assertFalse(holdsBlueprint(client), "the undo removed the synced blueprint");
        assertFalse(holdsStock(client, material), "the undo booked the synced lot out");

        app.locator("[data-ca-disconnect-client]").click();
        page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
        assertThat(app).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "undo and disconnect update the page in place");
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-round-trip");
        throw failure;
      }
    }

    ExchangeTestClient.Answer refused =
        awaitAnswer(client, "GET", "/exchange/v1/me/blueprints", a -> a.status() == 401);
    assertEquals(
        "CLIENT_REVOKED", refused.code(), "the disconnected client is refused: " + refused);
  }

  /**
   * Signs the member in on Keycloak's device page and grants the consent, in a fresh browser
   * context, until neither a login form, a code form nor a consent form is left.
   *
   * @param verificationUriComplete the device page with the user code
   */
  private static void approveOnTheDevicePage(String verificationUriComplete) {
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        page.navigate(verificationUriComplete);
        for (int step = 0; step < 6; step++) {
          page.waitForLoadState(LoadState.LOAD);
          Locator accept = page.locator("input[name='accept']");
          Locator password = page.locator("#password");
          Locator userCode = page.locator("input[name='device_user_code']");
          if (accept.count() > 0) {
            accept.click();
            detached(accept);
          } else if (password.count() > 0) {
            E2eSupport.submitKeycloakLogin(page, MEMBER, MEMBER_PASSWORD);
            detached(password);
          } else if (userCode.count() > 0) {
            page.locator("#kc-user-verify-device-user-code-form input[type='submit']").click();
            detached(userCode);
          } else {
            return;
          }
        }
        throw new AssertionError("the device page did not finish within six steps");
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-device-page");
        throw failure;
      }
    }
  }

  /**
   * Waits until a form element has left the page after its submit.
   *
   * @param element the element of the submitted form
   */
  private static void detached(Locator element) {
    element
        .first()
        .waitFor(
            new Locator.WaitForOptions()
                .setState(WaitForSelectorState.DETACHED)
                .setTimeout(30_000));
  }

  /**
   * Registers the client with the round trip's capabilities, or re-activates it when an earlier run
   * on the same stack left it suspended.
   *
   * @param seeder the backend seeder
   */
  private static void registerClient(BackendSeeder seeder) {
    JsonArray clients =
        JsonParser.parseString(
                seeder.getBody(ADMIN, ADMIN_PASSWORD, "/api/v1/admin/exchange-clients"))
            .getAsJsonArray();
    for (JsonElement element : clients) {
      JsonObject existing = element.getAsJsonObject();
      if (CLIENT_ID.equals(existing.get("clientId").getAsString())) {
        if (!"ACTIVE".equals(existing.get("status").getAsString())) {
          seeder.postBody(
              ADMIN,
              ADMIN_PASSWORD,
              "/api/v1/admin/exchange-clients/" + existing.get("id").getAsString() + "/activate",
              "{\"version\":" + existing.get("version").getAsLong() + "}");
        }
        return;
      }
    }
    JsonObject request = new JsonObject();
    request.addProperty("clientId", CLIENT_ID);
    request.addProperty("displayName", CLIENT_NAME);
    JsonArray capabilities = new JsonArray();
    CAPABILITIES.forEach(capabilities::add);
    request.add("capabilities", capabilities);
    seeder.postBody(ADMIN, ADMIN_PASSWORD, "/api/v1/admin/exchange-clients", request.toString());
  }

  /**
   * Switches the global exchange switch on when it is off.
   *
   * @param seeder the backend seeder
   */
  private static void switchExchangeOn(BackendSeeder seeder) {
    JsonObject settings =
        JsonParser.parseString(
                seeder.getBody(ADMIN, ADMIN_PASSWORD, "/api/v1/admin/exchange-settings"))
            .getAsJsonObject();
    if (settings.get("enabled").getAsBoolean()) {
      return;
    }
    int status =
        seeder.putForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/admin/exchange-settings",
            "{\"enabled\":true,\"version\":" + settings.get("version").getAsLong() + "}");
    assertEquals(200, status, "the exchange switch turns on");
  }

  /**
   * Repeats a gateway call until its answer matches, for a change the gateway sees through its
   * registry mirror.
   *
   * @param client the exchange client
   * @param method the HTTP method
   * @param path the gateway path
   * @param done the condition the answer must meet
   * @return the matching answer
   * @throws Exception if a call cannot be sent or no answer matches within {@link #MIRROR_WAIT}
   */
  private static ExchangeTestClient.Answer awaitAnswer(
      ExchangeTestClient client,
      String method,
      String path,
      Predicate<ExchangeTestClient.Answer> done)
      throws Exception {
    Instant deadline = Instant.now().plus(MIRROR_WAIT);
    ExchangeTestClient.Answer answer = client.call(method, path, null);
    while (!done.test(answer) && Instant.now().isBefore(deadline)) {
      Thread.sleep(2_000);
      answer = client.call(method, path, null);
    }
    assertTrue(done.test(answer), method + " " + path + " never answered as expected: " + answer);
    return answer;
  }

  /**
   * Builds a change set that adds one blueprint, observed in the game log.
   *
   * @param ref the resolved blueprint reference
   * @return the change set
   */
  private static JsonObject addBlueprint(JsonObject ref) {
    JsonObject provenance = new JsonObject();
    provenance.addProperty("source", "log");
    JsonObject op = new JsonObject();
    op.addProperty("op", "add");
    op.add("ref", ref);
    op.add("provenance", provenance);
    return changeSet(op);
  }

  /**
   * Builds a change set that raises the member's lot of a material from nothing to five SCU.
   *
   * @param material the resolved material reference
   * @return the change set
   */
  private static JsonObject bookIn(JsonObject material) {
    JsonObject location = new JsonObject();
    location.addProperty("name", LOCATION_NAME);
    JsonObject op = new JsonObject();
    op.addProperty("op", "set-quantity");
    op.add("material", material);
    op.add("location", location);
    op.addProperty("quality", 500);
    op.addProperty("stolen", false);
    op.add("quantity", quantity(5));
    op.add("expectedQuantity", quantity(0));
    return changeSet(op);
  }

  /**
   * Builds an SCU quantity.
   *
   * @param amount the amount
   * @return {@code {amount, unit}}
   */
  private static JsonObject quantity(double amount) {
    JsonObject quantity = new JsonObject();
    quantity.addProperty("amount", amount);
    quantity.addProperty("unit", "SCU");
    return quantity;
  }

  /**
   * Wraps one op into a change set.
   *
   * @param op the op
   * @return {@code {"ops": [op]}}
   */
  private static JsonObject changeSet(JsonObject op) {
    JsonArray ops = new JsonArray();
    ops.add(op);
    JsonObject changeSet = new JsonObject();
    changeSet.add("ops", ops);
    return changeSet;
  }

  /**
   * Reads whether the member's blueprint set holds the seeded blueprint.
   *
   * @param client the exchange client
   * @return {@code true} when the set lists it
   * @throws Exception if the call cannot be sent
   */
  private static boolean holdsBlueprint(ExchangeTestClient client) throws Exception {
    ExchangeTestClient.Answer answer = client.call("GET", "/exchange/v1/me/blueprints", null);
    assertOk(answer);
    for (JsonElement item : answer.body().getAsJsonArray("items")) {
      if (BLUEPRINT_NAME.equals(
          item.getAsJsonObject().getAsJsonObject("ref").get("name").getAsString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads whether the member holds any of the seeded material at the lot's location.
   *
   * @param client the exchange client
   * @param material the resolved material reference
   * @return {@code true} when a lot of it holds more than nothing
   * @throws Exception if the call cannot be sent
   */
  private static boolean holdsStock(ExchangeTestClient client, JsonObject material)
      throws Exception {
    ExchangeTestClient.Answer answer = client.call("GET", "/exchange/v1/me/stock", null);
    assertOk(answer);
    String bt = material.get("bt").getAsString();
    for (JsonElement item : answer.body().getAsJsonArray("items")) {
      JsonObject lot = item.getAsJsonObject();
      if (bt.equals(lot.getAsJsonObject("material").get("bt").getAsString())
          && LOCATION_NAME.equals(lot.getAsJsonObject("location").get("name").getAsString())
          && lot.getAsJsonObject("quantity").get("amount").getAsDouble() > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Asserts a gateway answer is a success.
   *
   * @param answer the answer
   */
  private static void assertOk(ExchangeTestClient.Answer answer) {
    assertTrue(answer.status() == 200 || answer.status() == 201, "expected success: " + answer);
  }

  /**
   * Asserts a change result applied its one op.
   *
   * @param answer the answer
   */
  private static void assertApplied(ExchangeTestClient.Answer answer) {
    assertEquals(200, answer.status(), "the change set is accepted: " + answer);
    assertEquals(1, answer.body().get("applied").getAsInt(), "the op is applied: " + answer);
    assertEquals(0, answer.body().get("notApplied").getAsInt(), "no op is refused: " + answer);
  }
}
