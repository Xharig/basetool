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

import de.greluc.krt.profit.basetool.backend.model.ExchangeChange;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and purges the trigger-written exchange change feed (REQ-XCH-013, ADR-0224). */
public interface ExchangeChangeRepository extends JpaRepository<ExchangeChange, Long> {

  /**
   * Returns the highest sequence number of the entries older than a cutoff.
   *
   * @param cutoff the oldest change still kept
   * @return the sequence number, {@code 0} when no entry is that old
   */
  @Query("SELECT COALESCE(MAX(c.seq), 0) FROM ExchangeChange c WHERE c.changedAt < :cutoff")
  long maxSeqBefore(@Param("cutoff") Instant cutoff);

  /**
   * Deletes every entry up to and including a sequence number.
   *
   * @param seq the highest sequence number to delete
   * @return the number of entries deleted
   */
  @Modifying
  @Query(value = "DELETE FROM exchange_change WHERE seq <= :seq", nativeQuery = true)
  int deleteThrough(@Param("seq") long seq);

  /**
   * Lists a member's entries in sequence order.
   *
   * @param userId the member
   * @return the entries
   */
  List<ExchangeChange> findAllByUserIdOrderBySeqAsc(UUID userId);

  /**
   * Returns the highest sequence number, the position a snapshot taken now starts the feed at.
   *
   * @return the sequence number, {@code 0} while the log is empty
   */
  @Query("SELECT COALESCE(MAX(c.seq), 0) FROM ExchangeChange c")
  long maxSeq();

  /**
   * Lists the keys of one member's resource changed after a position, each with its latest change,
   * in the order of those latest changes.
   *
   * @param userId the member
   * @param resource the resource
   * @param after the position
   * @param pageable the page size
   * @return the changed keys
   */
  @Query(
      """
      SELECT c.entityKey AS entityKey, MAX(c.seq) AS lastSeq FROM ExchangeChange c
      WHERE c.userId = :userId AND c.resource = :resource AND c.seq > :after
      GROUP BY c.entityKey ORDER BY MAX(c.seq)
      """)
  List<ChangedKey> findChangedKeys(
      @Param("userId") UUID userId,
      @Param("resource") ExchangeResource resource,
      @Param("after") long after,
      Pageable pageable);

  /** A changed key and its latest change, from {@link #findChangedKeys}. */
  interface ChangedKey {

    /**
     * Returns the entity key.
     *
     * @return the key
     */
    String getEntityKey();

    /**
     * Returns the sequence number of the key's latest change.
     *
     * @return the sequence number
     */
    long getLastSeq();
  }
}
