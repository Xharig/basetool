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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the exchange client registry (REQ-XCH-003). */
public interface ExchangeClientRepository extends JpaRepository<ExchangeClient, UUID> {

  /**
   * Loads every registry client with its capabilities in one query, ordered by client id.
   *
   * @return all clients
   */
  @EntityGraph(attributePaths = "capabilities")
  @Query("SELECT c FROM ExchangeClient c ORDER BY c.clientId")
  List<ExchangeClient> findAllWithCapabilities();

  /**
   * Loads one client with its capabilities.
   *
   * @param id the registry id
   * @return the client, or empty
   */
  @EntityGraph(attributePaths = "capabilities")
  @Query("SELECT c FROM ExchangeClient c WHERE c.id = :id")
  Optional<ExchangeClient> findWithCapabilitiesById(@Param("id") UUID id);

  /**
   * Tells whether a client id is already registered.
   *
   * @param clientId the Keycloak client id
   * @return {@code true} when a row carries it
   */
  boolean existsByClientId(String clientId);
}
