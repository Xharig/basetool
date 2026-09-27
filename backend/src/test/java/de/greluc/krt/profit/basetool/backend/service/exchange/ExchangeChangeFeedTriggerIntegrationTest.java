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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeChange;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The change feed's triggers against Postgres: every write path to a synced table is sequenced,
 * attributed from the transaction variable, and a default-set change reaches every owner
 * (REQ-XCH-013, ADR-0224).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExchangeChangeFeedTriggerIntegrationTest {

  /** Every table whose writes the feed must see, with the triggers that sequence them. */
  private static final Set<String> SYNCED_TABLES =
      Set.of("personal_blueprint", "default_blueprint");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeChangeRepository changeRepository;

  private UUID alice;
  private UUID bob;

  @BeforeEach
  void setUp() {
    alice = user("feed-alice");
    bob = user("feed-bob");
  }

  @Test
  void everySyncedTableCarriesAnExchangeTrigger() {
    List<String> triggered =
        jdbc.queryForList(
            """
            SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
            WHERE NOT t.tgisinternal AND t.tgname LIKE 'trg_%_exchange_change'
            """,
            String.class);

    assertThat(triggered).containsExactlyInAnyOrderElementsOf(SYNCED_TABLES);
  }

  @Test
  void anInsertOutsideAnyRequestIsSequencedAsSystem() {
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getResource()).isEqualTo(ExchangeResource.BLUEPRINT);
              assertThat(c.getEntityKey()).isEqualTo("laser rifle");
              assertThat(c.getSourceChannel()).isEqualTo("system");
              assertThat(c.getSourceClient()).isNull();
            });
  }

  @Test
  void aClientWriteIsAttributedToItsClientAndKey() {
    source("client|versekit|" + "K".repeat(43));
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getSourceChannel()).isEqualTo("client");
              assertThat(c.getSourceClient()).isEqualTo("versekit");
              assertThat(c.getSourceKey()).isEqualTo("K".repeat(43));
            });
  }

  @Test
  void anUnknownChannelFallsBackToSystem() {
    source("forged|x|y");
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(c -> assertThat(c.getSourceChannel()).isEqualTo("system"));
  }

  @Test
  void aBulkDeleteSequencesEveryRow() {
    blueprint(alice, "a");
    blueprint(alice, "b");
    source("web");

    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", alice);

    assertThat(changes(alice))
        .filteredOn(c -> "web".equals(c.getSourceChannel()))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void anOwnerReassignmentRemovesFromOneAndAddsToTheOther() {
    blueprint(alice, "a");
    int before = changes(bob).size();

    jdbc.update(
        "UPDATE personal_blueprint SET owner_user_id = ? WHERE owner_user_id = ?", bob, alice);

    assertThat(changes(alice)).extracting(ExchangeChange::getEntityKey).containsExactly("a", "a");
    assertThat(changes(bob)).hasSize(before + 1);
  }

  @Test
  void aNoteEditIsOneEntry() {
    blueprint(alice, "a");

    jdbc.update("UPDATE personal_blueprint SET note = 'mine' WHERE owner_user_id = ?", alice);

    assertThat(changes(alice)).hasSize(2);
  }

  @Test
  void aDefaultSetChangeReachesEveryOwnerOfTheProduct() {
    blueprint(alice, "shared");
    blueprint(bob, "shared");
    blueprint(bob, "other");
    int aliceBefore = changes(alice).size();
    int bobBefore = changes(bob).size();

    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, 'shared',"
            + " 'Shared')",
        UUID.randomUUID());
    jdbc.update("DELETE FROM default_blueprint WHERE product_key = 'shared'");

    assertThat(changes(alice)).hasSize(aliceBefore + 2);
    assertThat(changes(bob).subList(bobBefore, changes(bob).size()))
        .extracting(ExchangeChange::getEntityKey)
        .containsOnly("shared");
  }

  @Test
  void deletingAMemberDropsTheirFeedWithoutFailing() {
    blueprint(alice, "a");

    jdbc.update("DELETE FROM app_user WHERE id = ?", alice);

    assertThat(changes(alice)).isEmpty();
  }

  /**
   * Sets the transaction variable the triggers read.
   *
   * @param source the source
   */
  private void source(@NotNull String source) {
    jdbc.queryForObject(
        "SELECT set_config('basetool.change_source', ?, true)", String.class, source);
  }

  /**
   * Inserts a personal blueprint directly, as a bulk path would.
   *
   * @param owner the owner
   * @param productKey the product key
   */
  private void blueprint(@NotNull UUID owner, @NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
  }

  /**
   * Lists a member's feed entries in sequence order.
   *
   * @param member the member
   * @return the entries
   */
  private @NotNull List<ExchangeChange> changes(@NotNull UUID member) {
    return changeRepository.findAllByUserIdOrderBySeqAsc(member);
  }

  /**
   * Seeds a member.
   *
   * @param username the username
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }
}
