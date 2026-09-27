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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.BlueprintSource;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Integration tests that every way a blueprint is added records where it came from. */
@SpringBootTest
@ActiveProfiles("test")
class PersonalBlueprintProvenanceTest {

  @Autowired private PersonalBlueprintService blueprintService;
  @Autowired private BlueprintImportService importService;
  @Autowired private PersonalBlueprintRepository personalBlueprintRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private DefaultBlueprintKeyService defaultKeys;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID member;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<String> defaults = new ArrayList<>();

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("bp-source-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    defaults.forEach(k -> jdbc.update("DELETE FROM default_blueprint WHERE product_key = ?", k));
    defaultKeys.refresh();
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
  }

  @Test
  void theWebRecordsHandAddsImportsAndDefaultGrants() {
    String single = product("Arrowhead Rifle");
    String batched = product("Arclight Pistol");
    String imported = product("Oracle Helmet");
    String granted = product("Pembroke Armor");
    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, ?, 'Pembroke')",
        UUID.randomUUID(),
        granted);
    defaults.add(granted);
    defaultKeys.refresh();

    PersonalBlueprintResponse added =
        blueprintService.add(member, new PersonalBlueprintCreateRequest(single, null, null));
    blueprintService.addBatch(member, List.of(batched));
    importService.applyImport(
        member, List.of(new BlueprintImportResolutionDto("Oracle Helmet", imported, null, null)));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> personalBlueprintRepository.grantDefaultBlueprintsToUser(member));

    assertThat(added.source()).isEqualTo(BlueprintSource.MANUAL);
    assertThat(sources())
        .contains(
            single + ":MANUAL", batched + ":MANUAL", imported + ":IMPORT", granted + ":DEFAULT");
  }

  /**
   * Reads each of the member's blueprints with its source and client.
   *
   * @return {@code key:source}, with {@code :client} appended when one is recorded
   */
  private @NotNull List<String> sources() {
    return jdbc.queryForList(
        "SELECT product_key || ':' || source || COALESCE(':' || source_client_id, '')"
            + " FROM personal_blueprint WHERE owner_user_id = ?",
        String.class,
        member);
  }

  /**
   * Seeds an active catalogue product with a unique name.
   *
   * @param name the name's stem
   * @return its product key
   */
  private @NotNull String product(@NotNull String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName(name + " " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    return normalizer.normalize(saved.getOutputName());
  }
}
