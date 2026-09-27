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

package de.greluc.krt.profit.basetool.backend.controller.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A client's stock changes, relayed from the ingest gateway: lots set against the quantity the
 * client last saw, booked in and out like the Lager, Materialbörse offers following a book-out, and
 * the mass-change guard (REQ-XCH-014, REQ-XCH-016, REQ-XCH-021). Writes commit.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeStockWriteControllerTest {

  private static final String PATH = "/api/v1/exchange/me/stock/changes";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "t".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> items = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> cities = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("stock-writer-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_WRITE));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    items.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    cities.forEach(id -> jdbc.update("DELETE FROM city WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aNewLotIsBookedInWithoutAnOrgUnitAndJournaled() throws Exception {
    UUID laranite = material("SCU", null);
    String area18 = locationName(location(null));

    set(laranite, area18, 712, "12.5", "0", "SCU")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.offersReduced").value(0))
        .andExpect(jsonPath("$.offersRemoved").value(0));

    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND personal"
                    + " AND owning_org_unit_id IS NULL AND quality = 712",
                Double.class,
                member))
        .containsExactly(12.5);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getAction().name()).isEqualTo("STOCK_SET_QUANTITY");
              assertThat(e.getAfterState()).contains("12.5");
            });
  }

  @Test
  void anExpectationTheLotNoLongerHoldsIsAVersionConflict() throws Exception {
    UUID laranite = material("SCU", null);
    UUID area18 = location(null);
    row(laranite, area18, 500, 10, null);

    set(laranite, locationName(area18), 500, "4", "8", "SCU")
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));

    assertThat(total(laranite)).isEqualTo(10.0);
  }

  @Test
  void aBookOutTakesTheRowsWithoutAnOrgUnitFirstAndLowersTheirOffers() throws Exception {
    UUID laranite = material("SCU", null);
    UUID area18 = location(null);
    UUID unit = jdbc.queryForObject("SELECT id FROM org_unit LIMIT 1", UUID.class);
    UUID pooled = row(laranite, area18, 500, 6, unit);
    UUID loose = row(laranite, area18, 500, 4, null);
    UUID offer = offer(loose, 3.0);

    set(laranite, locationName(area18), 500, "7.5", "10", "SCU")
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.offersReduced").value(1))
        .andExpect(jsonPath("$.offersRemoved").value(0));

    assertThat(amount(loose)).isEqualTo(1.5);
    assertThat(amount(pooled)).isEqualTo(6.0);
    assertThat(
            jdbc.queryForObject(
                "SELECT offered_amount FROM material_exchange_offer WHERE id = ?",
                Double.class,
                offer))
        .isEqualTo(1.5);
    assertThat(audit("MARKET_OFFER_REDUCED", offer)).isEqualTo(1);
  }

  @Test
  void emptyingARowRemovesItsOfferAndAuditsIt() throws Exception {
    UUID laranite = material("SCU", null);
    UUID area18 = location(null);
    UUID loose = row(laranite, area18, 500, 4, null);
    UUID offer = offer(loose, 4.0);

    set(laranite, locationName(area18), 500, "0", "4", "SCU")
        .andExpect(jsonPath("$.offersRemoved").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_exchange_offer WHERE id = ?", Integer.class, offer))
        .isZero();
    assertThat(audit("MARKET_OFFER_REMOVED", offer)).isEqualTo(1);
  }

  @Test
  void aPlaceIsFoundByItsUexCityAndAnItemLotCountsPieces() throws Exception {
    UUID helmet = item();
    UUID orison = location(1_000_000 + (int) (Math.random() * 1_000_000));
    int uexCity =
        jdbc.queryForObject(
            "SELECT c.id_city FROM location l JOIN city c ON c.id = l.city_id WHERE l.id = ?",
            Integer.class,
            orison);

    change(
            """
            {"ops":[{"op":"set-quantity","material":{"bt":"%s"},
                     "location":{"uex":{"kind":"CITY","id":%d}},"quality":0,"stolen":false,
                     "quantity":{"amount":2,"unit":"PIECE"},
                     "expectedQuantity":{"amount":0,"unit":"PIECE"}}]}
            """
                .formatted(helmet, uexCity))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND game_item_id = ?"
                    + " AND location_id = ? AND quality IS NULL",
                Double.class,
                member,
                helmet,
                orison))
        .containsExactly(2.0);
  }

  @Test
  void aWrongUnitAndAnUnknownPlaceAreRejected() throws Exception {
    UUID laranite = material("SCU", null);
    String area18 = locationName(location(null));

    set(laranite, area18, 500, "2", "0", "PIECE")
        .andExpect(jsonPath("$.results[0].reason").value("UNIT_MISMATCH"));
    set(laranite, "Nowhere " + UUID.randomUUID(), 500, "2", "0", "SCU")
        .andExpect(jsonPath("$.results[0].reason").value("LOCATION_UNKNOWN"));
  }

  @Test
  void aTradeGoodIsStoredAtQualityZero() throws Exception {
    UUID agricium = material("SCU", 900_000 + (int) (Math.random() * 90_000));
    String area18 = locationName(location(null));

    set(agricium, area18, 740, "3", "0", "SCU").andExpect(jsonPath("$.applied").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT quality FROM inventory_item WHERE user_id = ? AND material_id = ?",
                Integer.class,
                member,
                agricium))
        .isZero();
  }

  @Test
  void refillingALotEmptiedInTheWebNeedsTheOverride() throws Exception {
    UUID laranite = material("SCU", null);
    UUID area18 = location(null);
    row(laranite, area18, 500, 4, null);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              jdbc.queryForObject(
                  "SELECT set_config('basetool.change_source', 'web', true)", String.class);
              jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
            });

    set(laranite, locationName(area18), 500, "4", "0", "SCU")
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"));

    change(
            """
            {"ops":[{"op":"set-quantity","material":{"bt":"%s"},"location":{"name":"%s"},
                     "quality":500,"stolen":false,"quantity":{"amount":4,"unit":"SCU"},
                     "expectedQuantity":{"amount":0,"unit":"SCU"},"override":true}]}
            """
                .formatted(laranite, locationName(area18)))
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(total(laranite)).isEqualTo(4.0);
  }

  @Test
  void emptyingEveryLotIsHeldBackButAMoveIsNot() throws Exception {
    UUID laranite = material("SCU", null);
    List<String> places = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID place = location(null);
      row(laranite, place, 500, 10, null);
      places.add(locationName(place));
    }
    StringBuilder emptying = new StringBuilder();
    for (String place : places) {
      emptying
          .append(emptying.isEmpty() ? "" : ",")
          .append(op(laranite, place, 500, "0", "10", "SCU"));
    }

    change("{\"ops\":[" + emptying + "]}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));
    assertThat(total(laranite)).isEqualTo(50.0);

    String target = locationName(location(null));
    change("{\"ops\":[" + emptying + "," + op(laranite, target, 500, "50", "0", "SCU") + "]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(6));
    assertThat(total(laranite)).isEqualTo(50.0);
  }

  @Test
  void withoutTheWriteCapabilityTheChangeIsRefused() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"ops\":[" + op(UUID.randomUUID(), "Anywhere", 1, "1", "0", "SCU") + "]}")
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect")
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andExpect(status().isForbidden());
  }

  /**
   * Sets one lot.
   *
   * @param material the material
   * @param place the location name
   * @param quality the quality
   * @param quantity the new quantity
   * @param expected the expected quantity
   * @param unit the unit of both
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions set(
      @NotNull UUID material,
      @NotNull String place,
      int quality,
      @NotNull String quantity,
      @NotNull String expected,
      @NotNull String unit)
      throws Exception {
    return change("{\"ops\":[" + op(material, place, quality, quantity, expected, unit) + "]}");
  }

  /**
   * Builds one set-quantity op.
   *
   * @param material the material
   * @param place the location name
   * @param quality the quality
   * @param quantity the new quantity
   * @param expected the expected quantity
   * @param unit the unit of both
   * @return the op as JSON
   */
  private static @NotNull String op(
      @NotNull UUID material,
      @NotNull String place,
      int quality,
      @NotNull String quantity,
      @NotNull String expected,
      @NotNull String unit) {
    return """
    {"op":"set-quantity","material":{"bt":"%s"},"location":{"name":"%s"},"quality":%d,
     "stolen":false,"quantity":{"amount":%s,"unit":"%s"},
     "expectedQuantity":{"amount":%s,"unit":"%s"}}\
    """
        .formatted(material, place, quality, quantity, unit, expected, unit);
  }

  /**
   * Posts a change set as the test client.
   *
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions change(@NotNull String json) throws Exception {
    return mockMvc.perform(
        post(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
            .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
            .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
            .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.stock.write")
            .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY));
  }

  /**
   * Seeds a personal Lager row.
   *
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount
   * @param unit the owning org unit, or {@code null}
   * @return the row
   */
  private @NotNull UUID row(
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount,
      @Nullable UUID unit) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, true, false, ?, ?, 0)
        """,
        id,
        member,
        material,
        location,
        quality,
        amount,
        unit,
        Timestamp.from(Instant.now().minusSeconds(unit == null ? 10 : 3600)));
    return id;
  }

  /**
   * Offers part of a row on the Materialbörse.
   *
   * @param inventoryItem the row
   * @param amount the offered amount
   * @return the offer
   */
  private @NotNull UUID offer(@NotNull UUID inventoryItem, double amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, status, released_at,
                                             version, created_at, updated_at, offered_amount,
                                             offer_kind)
        VALUES (?, ?, ?, 'ACTIVE', now(), 0, now(), now(), ?, 'MATERIAL')
        """,
        id,
        inventoryItem,
        member,
        amount);
    return id;
  }

  /**
   * Seeds a material.
   *
   * @param unit the unit
   * @param commodity the UEX commodity id, or {@code null}
   * @return its id
   */
  private @NotNull UUID material(@NotNull String unit, @Nullable Integer commodity) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, id_commodity, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'REFINED', ?, ?, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock write " + id,
        unit,
        commodity);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a game item.
   *
   * @return its id
   */
  private @NotNull UUID item() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        id,
        "Helmet " + id);
    items.add(id);
    return id;
  }

  /**
   * Seeds a location, linked to a new UEX city when an id is given.
   *
   * @param uexCity the UEX city id, or {@code null}
   * @return its id
   */
  private @NotNull UUID location(@Nullable Integer uexCity) {
    UUID city = null;
    if (uexCity != null) {
      city = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO city (id, id_city, name) VALUES (?, ?, ?)", city, uexCity, "City " + city);
      cities.add(city);
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id) VALUES (?, ?, false, ?)",
        id,
        "Place " + id,
        city);
    locations.add(id);
    return id;
  }

  private @NotNull String locationName(@NotNull UUID location) {
    return jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, location);
  }

  private double amount(@NotNull UUID row) {
    return jdbc.queryForObject("SELECT amount FROM inventory_item WHERE id = ?", Double.class, row);
  }

  private double total(@NotNull UUID material) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ? AND material_id"
                + " = ?",
            Double.class,
            member,
            material);
    return sum == null ? 0 : sum;
  }

  private int audit(@NotNull String type, @NotNull UUID subject) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
        Integer.class,
        type,
        subject);
  }
}
