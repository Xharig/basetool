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
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.changeSet;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.heldAmount;
import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.setQuantity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The exchange's sync flows end to end (REQ-XCH-015, REQ-XCH-021, REQ-XCH-032): the anonymised
 * game-log corpus added through the exchange reaches „Meine Blueprints" and the feed of another
 * installation, and a mass removal is held until the member confirms it in the browser.
 */
@Tag("e2e")
class ExchangeSyncE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** The member who syncs; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange-3";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-3-pw";

  /** The corpus of blueprint unlocks from real game logs, shared with the backend's tests. */
  private static final String CORPUS =
      "backend/src/test/resources/fixtures/blueprint-corpus/game-log-corpus-v1.json";

  /** Corpus names that equal a seeded product exactly and so must resolve. */
  private static final Set<String> EXACT_NAMES =
      Set.of(
          "Monde Legs Delta Camo",
          "Monde Arms Hemlock Camo",
          "Strata Arms Levski Edition",
          "Strata Helmet Levski Edition",
          "Pitman Mining Laser");

  /** The qualities of the five lots the mass removal empties. */
  private static final List<Integer> QUALITIES = List.of(100, 200, 300, 400, 500);

  private static Playwright playwright;
  private static Browser browser;

  /** Seeds the catalogue rows and the corpus products, homes the member and switches it on. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "the flows need the local stack's gateway and admin API");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    BackendSeeder seeder = new BackendSeeder();
    seeder.seedSql("/exchange-corpus-e2e-seed.sql");
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
   * Resolves every corpus name, adds the resolved products through one installation, and finds each
   * in the feed of a second installation and on „Meine Blueprints", named as the client's.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void theCorpusReachesMeineBlueprintsAndTheFeedOfAnotherInstallation() throws Exception {
    ExchangeTestClient reader = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(reader, "E2E corpus reader");
    String cursor = snapshotCursor(reader);
    ExchangeTestClient writer = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(writer, "E2E corpus writer");

    Map<String, JsonObject> products = resolveCorpus(writer);
    Set<String> names = new HashSet<>();
    products.values().forEach(ref -> names.add(ref.get("name").getAsString()));
    assertTrue(names.containsAll(EXACT_NAMES), "every exact corpus name resolves: " + names);

    List<JsonObject> adds = new ArrayList<>();
    products.values().forEach(ref -> adds.add(addBlueprint(ref)));
    assertApplied(
        writer.call("POST", "/exchange/v1/me/blueprints/changes", changeSet(adds)), adds.size());

    Set<String> fed = feedAdditions(reader, cursor);
    assertTrue(
        fed.containsAll(products.keySet()),
        "the other installation's feed names every added product: " + fed);

    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/personal-inventory/blueprints");
        page.waitForLoadState();
        String rows =
            (String)
                page.locator("#krt-bp-master-rows .master-row")
                    .evaluateAll(
                        "rows => JSON.stringify(rows.map(r =>"
                            + " [r.dataset.name, r.dataset.sourceClient || '']))");
        Map<String, String> sourceByName = new HashMap<>();
        for (JsonElement row : JsonParser.parseString(rows).getAsJsonArray()) {
          JsonArray cells = row.getAsJsonArray();
          sourceByName.put(cells.get(0).getAsString(), cells.get(1).getAsString());
        }
        for (String name : names) {
          assertEquals(
              CLIENT_ID,
              sourceByName.get(name),
              name + " is on „Meine Blueprints\" as the client's: " + sourceByName);
        }
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-corpus");
        throw failure;
      }
    }
  }

  /**
   * Books five lots in, sends a batch that empties all of them, and checks the gateway holds it
   * back; the member then confirms it on the page the confirmation link opens, after which the lots
   * are empty.
   *
   * @throws Exception if a call to Keycloak or the gateway cannot be sent
   */
  @Test
  void aHeldMassRemovalAppliesOnlyOnceTheMemberConfirmsIt() throws Exception {
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    ExchangeE2eSupport.label(client, "E2E mass change");
    JsonObject materialRef = new JsonObject();
    materialRef.addProperty("name", MATERIAL_NAME);
    JsonObject material = client.resolve("MATERIAL", materialRef);

    List<JsonObject> bookIn = new ArrayList<>();
    List<JsonObject> empty = new ArrayList<>();
    for (int quality : QUALITIES) {
      bookIn.add(setQuantity(material, quality, 5, heldAmount(client, material, quality)));
      empty.add(setQuantity(material, quality, 0, 5));
    }
    assertApplied(
        client.call("POST", "/exchange/v1/me/stock/changes", changeSet(bookIn)), bookIn.size());

    ExchangeTestClient.Answer held =
        client.call("POST", "/exchange/v1/me/stock/changes", changeSet(empty));
    assertEquals(409, held.status(), "the mass removal is held back: " + held);
    assertEquals("MASS_CHANGE_CONFIRMATION_REQUIRED", held.code(), "held for the member: " + held);
    for (int quality : QUALITIES) {
      assertEquals(5.0, heldAmount(client, material, quality), "nothing is written before");
    }
    URI link = URI.create(held.body().get("confirmationUrl").getAsString());
    assertEquals("/connected-apps/confirm", link.getPath(), "the link opens the confirmation");

    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + link.getRawPath() + "?" + link.getRawQuery());
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='cac-summary']")).isVisible();
        assertThat(page.locator("[data-testid='cac-summary']")).containsText(CLIENT_NAME);
        page.locator("[data-testid='cac-confirm']").click();
        assertThat(page.locator("[data-testid='cac-outcome']")).isVisible();
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-mass-change");
        throw failure;
      }
    }
    for (int quality : QUALITIES) {
      assertEquals(
          0.0, heldAmount(client, material, quality), "the confirmed batch emptied the lot");
    }
  }

  /**
   * Resolves every product name of the corpus in one call and keeps the resolved ones.
   *
   * @param client the connected client
   * @return the resolved references by their {@code bt}, in corpus order
   * @throws Exception if the corpus cannot be read or the call cannot be sent
   */
  private static Map<String, JsonObject> resolveCorpus(ExchangeTestClient client) throws Exception {
    JsonArray refs = new JsonArray();
    for (JsonElement unlock : corpus().getAsJsonArray("blueprints")) {
      JsonObject ref = new JsonObject();
      ref.addProperty("name", unlock.getAsJsonObject().get("productName").getAsString());
      refs.add(ref);
    }
    Map<String, JsonObject> resolved = new LinkedHashMap<>();
    for (JsonElement element : client.resolveAll("BLUEPRINT", refs)) {
      JsonObject result = element.getAsJsonObject();
      if ("resolved".equals(result.get("status").getAsString())) {
        JsonObject ref = result.getAsJsonObject("ref");
        resolved.putIfAbsent(ref.get("bt").getAsString(), ref);
      }
    }
    return resolved;
  }

  /**
   * Reads the corpus from the backend's test fixtures.
   *
   * @return the corpus document
   * @throws IOException if the file cannot be read
   */
  private static JsonObject corpus() throws IOException {
    return JsonParser.parseString(
            Files.readString(E2eStackExtension.repoRoot().resolve(CORPUS), StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  /**
   * Reads the member's whole blueprint snapshot and returns the cursor its feed continues from.
   *
   * @param client the connected client
   * @return the cursor of the snapshot's last page
   * @throws Exception if a call cannot be sent
   */
  private static String snapshotCursor(ExchangeTestClient client) throws Exception {
    ExchangeTestClient.Answer page = client.call("GET", "/exchange/v1/me/blueprints", null);
    assertOk(page);
    while (page.body().get("hasMore").getAsBoolean()) {
      page = client.call("GET", blueprintsAfter(page), null);
      assertOk(page);
    }
    return page.body().get("nextCursor").getAsString();
  }

  /**
   * Reads the feed after a cursor to its end and collects the keys of the added blueprints.
   *
   * @param client the connected client
   * @param cursor the cursor to read from
   * @return the {@code key} of every blueprint the feed names as present
   * @throws Exception if a call cannot be sent
   */
  private static Set<String> feedAdditions(ExchangeTestClient client, String cursor)
      throws Exception {
    Set<String> keys = new HashSet<>();
    ExchangeTestClient.Answer page =
        client.call("GET", "/exchange/v1/me/blueprints?cursor=" + encode(cursor), null);
    assertOk(page);
    collectKeys(page, keys);
    while (page.body().get("hasMore").getAsBoolean()) {
      page = client.call("GET", blueprintsAfter(page), null);
      assertOk(page);
      collectKeys(page, keys);
    }
    return keys;
  }

  /**
   * Adds the {@code key} of every item of a page.
   *
   * @param page the page
   * @param keys the keys collected so far
   */
  private static void collectKeys(ExchangeTestClient.Answer page, Set<String> keys) {
    for (JsonElement item : page.body().getAsJsonArray("items")) {
      keys.add(item.getAsJsonObject().get("key").getAsString());
    }
  }

  /**
   * Builds the path of the page after the given one.
   *
   * @param page the current page
   * @return the blueprints path with the page's next cursor
   */
  private static String blueprintsAfter(ExchangeTestClient.Answer page) {
    return "/exchange/v1/me/blueprints?cursor="
        + encode(page.body().get("nextCursor").getAsString());
  }

  /**
   * URL-encodes a query value.
   *
   * @param value the value
   * @return the encoded value
   */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
