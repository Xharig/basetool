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

import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the exchange installations and their deny-list entries (REQ-XCH-007, -008). */
public interface ExchangeInstallationRepository extends JpaRepository<ExchangeInstallation, UUID> {

  /**
   * Records that an installation was seen: creates it on first sight, otherwise moves its last-seen
   * time forward when it is older than the given threshold.
   *
   * @param clientId the Keycloak client id
   * @param userId the member
   * @param keyThumbprint the DPoP key thumbprint
   * @param now the current time
   * @param touchBefore last-seen times before this are moved to {@code now}
   * @return the number of rows written
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint,
              first_seen_at, last_seen_at, created_at, version)
          SELECT gen_random_uuid(), c.id, :userId, :keyThumbprint, :now, :now, :now, 0
          FROM exchange_client c WHERE c.client_id = :clientId
          ON CONFLICT (exchange_client_id, user_id, key_thumbprint)
          DO UPDATE SET last_seen_at = :now
          WHERE exchange_installation.last_seen_at < :touchBefore
          """,
      nativeQuery = true)
  int touch(
      @Param("clientId") String clientId,
      @Param("userId") UUID userId,
      @Param("keyThumbprint") String keyThumbprint,
      @Param("now") Instant now,
      @Param("touchBefore") Instant touchBefore);

  /**
   * Loads one member's installation of a client by its key.
   *
   * @param clientId the Keycloak client id
   * @param userId the member
   * @param keyThumbprint the DPoP key thumbprint
   * @return the installation, or empty
   */
  @Query(
      """
      SELECT i FROM ExchangeInstallation i
      WHERE i.client.clientId = :clientId AND i.user.id = :userId
        AND i.keyThumbprint = :keyThumbprint
      """)
  Optional<ExchangeInstallation> findByKey(
      @Param("clientId") String clientId,
      @Param("userId") UUID userId,
      @Param("keyThumbprint") String keyThumbprint);

  /**
   * Lists a member's installations with their clients, newest first.
   *
   * @param userId the member
   * @return the installations
   */
  @EntityGraph(attributePaths = "client")
  @Query(
      "SELECT i FROM ExchangeInstallation i WHERE i.user.id = :userId ORDER BY i.firstSeenAt DESC")
  List<ExchangeInstallation> findAllByUserId(@Param("userId") UUID userId);

  /**
   * Lists the installations revoked after a point in time, the live deny list.
   *
   * @param since the oldest revocation still denied
   * @return the revoked installations
   */
  @Query("SELECT i FROM ExchangeInstallation i WHERE i.revokedAt > :since")
  List<ExchangeInstallation> findRevokedSince(@Param("since") Instant since);
}
