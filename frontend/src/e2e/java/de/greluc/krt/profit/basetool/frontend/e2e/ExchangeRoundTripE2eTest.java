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
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.CLIENT_ID;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.CLIENT_NAME;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.MATERIAL_NAME;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.addBlueprint;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.assertApplied;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.assertOk;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.awaitAnswer;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.changeSet;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.heldAmount;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.setQuantity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.List;
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

  /** The member who connects the client; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-pw";

  /** The label the client gives its installation. */
  private static final String LABEL = "E2E round trip";

  /** The seeded blueprint's record key, see {@code exchange-e2e-seed.sql}. */
  private static final String BLUEPRINT_RECORD = "BP_CRAFT_E2EM_EXCHANGE_RIFLE_01";

  /** The seeded blueprint's product name. */
  private static final String BLUEPRINT_NAME = "E2E Exchange Rifle";

  /** The quality of the synced stock lot. */
  private static final int QUALITY = 500;

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
    ExchangeE2eSupport.prepare(new BackendSeeder(), MEMBER, MEMBER_PASSWORD);
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
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);

    JsonObject claims = client.accessTokenClaims();
    assertEquals(
        client.thumbprint(),
        claims.getAsJsonObject("cnf").get("jkt").getAsString(),
        "the token is bound to the installation key");
    assertTrue(
        claims.get("aud").toString().contains(E2eStackExtension.INGEST_AUDIENCE),
        "the token names the gateway audience: " + claims.get("aud"));

    ExchangeE2eSupport.label(client, LABEL);

    JsonObject blueprintRef = new JsonObject();
    blueprintRef.addProperty("scRecord", BLUEPRINT_RECORD);
    JsonObject blueprint = client.resolve("BLUEPRINT", blueprintRef);
    JsonObject materialRef = new JsonObject();
    materialRef.addProperty("name", MATERIAL_NAME);
    JsonObject material = client.resolve("MATERIAL", materialRef);

    assertApplied(
        client.call(
            "POST",
            "/exchange/v1/me/blueprints/changes",
            changeSet(List.of(addBlueprint(blueprint)))),
        1);
    assertApplied(
        client.call(
            "POST",
            "/exchange/v1/me/stock/changes",
            changeSet(List.of(setQuantity(material, QUALITY, 5, 0)))),
        1);
    assertTrue(holdsBlueprint(client), "the synced blueprint is in the member's set");
    assertTrue(
        heldAmount(client, material, QUALITY) > 0, "the synced lot is in the member's stock");

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
        assertEquals(
            0.0, heldAmount(client, material, QUALITY), "the undo booked the synced lot out");

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
}
