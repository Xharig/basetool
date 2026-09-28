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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeLocationRef;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two installations of one member that send stock change sets over the same lots in opposite orders
 * lock those lots in one order and never deadlock (REQ-XCH-016).
 */
@SpringBootTest
@ActiveProfiles("test")
class ExchangeStockWriteLockOrderIntegrationTest {

  private static final String CLIENT = "versekit-lock";

  @Autowired private ExchangeStockWriteService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @MockitoSpyBean private ExchangeLocationResolver locationResolver;

  private UUID member;
  private UUID material;
  private UUID location;
  private String locationName;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("lock-order-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "lock-order-" + material);
    locationName = "lock-order-" + UUID.randomUUID();
    Location place = new Location();
    place.setName(locationName);
    location = locationRepository.saveAndFlush(place).getId();
    stock(1);
    stock(2);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    jdbc.update("DELETE FROM location WHERE id = ?", location);
  }

  @Test
  void overlappingSetsInOppositeOrdersBothFinish() throws Exception {
    CyclicBarrier bothBetweenTheirLots = new CyclicBarrier(2);
    ThreadLocal<Integer> resolved = ThreadLocal.withInitial(() -> 0);
    doAnswer(
            invocation -> {
              resolved.set(resolved.get() + 1);
              if (resolved.get() == 2) {
                awaitBriefly(bothBetweenTheirLots);
              }
              return invocation.callRealMethod();
            })
        .when(locationResolver)
        .resolve(any());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ExchangeChangeResultDto> first =
          pool.submit(() -> service.apply(caller("A"), changeSet(1, 2)));
      Future<ExchangeChangeResultDto> second =
          pool.submit(() -> service.apply(caller("B"), changeSet(2, 1)));

      assertThat(first.get(60, TimeUnit.SECONDS)).isNotNull();
      assertThat(second.get(60, TimeUnit.SECONDS)).isNotNull();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Waits for the other thread at the barrier, and goes on alone when it does not come.
   *
   * @param barrier the barrier
   * @throws InterruptedException when the thread is interrupted
   */
  private static void awaitBriefly(@NotNull CyclicBarrier barrier) throws InterruptedException {
    try {
      barrier.await(3, TimeUnit.SECONDS);
    } catch (TimeoutException | BrokenBarrierException ignored) {
      barrier.reset();
    }
  }

  /**
   * Builds a change set that raises the lots of the given qualities by one, in that order.
   *
   * @param qualities the lots' qualities, in op order
   * @return the change set
   */
  private @NotNull ExchangeStockChangeSet changeSet(int... qualities) {
    List<ExchangeStockChangeSet.Op> ops =
        Arrays.stream(qualities)
            .mapToObj(
                quality ->
                    new ExchangeStockChangeSet.Op(
                        "q" + quality,
                        "set-quantity",
                        new ExchangeItemRef(
                            material.toString(), null, null, null, null, null, null),
                        new ExchangeLocationRef(locationName, null),
                        quality,
                        false,
                        new ExchangeStockChangeSet.Quantity(new BigDecimal("6"), "SCU"),
                        new ExchangeStockChangeSet.Quantity(new BigDecimal("5"), "SCU"),
                        null))
            .toList();
    return new ExchangeStockChangeSet(ops, false);
  }

  /**
   * Builds the caller of one installation of the member.
   *
   * @param installation the installation's distinguishing letter
   * @return the caller
   */
  private @NotNull ExchangeCaller caller(@NotNull String installation) {
    return new ExchangeCaller(member, CLIENT, installation.repeat(43));
  }

  /**
   * Seeds a personal lot of five SCU at a quality.
   *
   * @param quality the quality
   */
  private void stock(int quality) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen)
        VALUES (?, ?, ?, ?, ?, 5, true, false)
        """,
        UUID.randomUUID(),
        member,
        material,
        location,
        quality);
  }
}
