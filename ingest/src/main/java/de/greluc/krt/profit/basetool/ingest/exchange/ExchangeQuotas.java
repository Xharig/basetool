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

package de.greluc.krt.profit.basetool.ingest.exchange;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts each member's daily exchange writes per client in Redis, under {@code
 * ingest:xch:quota:<client>:<member>:<UTC day>} (REQ-XCH-023). A counter lives two days, so it
 * outlasts its own day wherever the gateway's clock stands, and counts in the byte budget from its
 * first write.
 */
@Slf4j
@Component
public class ExchangeQuotas {

  /** The key prefix of a daily counter. */
  static final String PREFIX = "ingest:xch:quota:";

  /** How long a counter lives. */
  static final Duration TTL = Duration.ofDays(2);

  /** The bytes a counter is budgeted with: its key plus a long's decimal digits. */
  static final int VALUE_BYTES = 20;

  private final StringRedisTemplate redisTemplate;
  private final ExchangeBudget budget;
  private final Clock clock;

  /**
   * Creates the quotas on the system clock.
   *
   * @param redisTemplate the Redis access
   * @param budget the byte budget a new counter is registered in
   */
  @Autowired
  public ExchangeQuotas(
      @NotNull StringRedisTemplate redisTemplate, @NotNull ExchangeBudget budget) {
    this(redisTemplate, budget, Clock.systemUTC());
  }

  /**
   * Creates the quotas on the given clock.
   *
   * @param redisTemplate the Redis access
   * @param budget the byte budget a new counter is registered in
   * @param clock the time source
   */
  ExchangeQuotas(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeBudget budget,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.budget = budget;
    this.clock = clock;
  }

  /**
   * Counts one write and returns the day's count so far.
   *
   * @param clientId the client
   * @param member the member
   * @return the count including this write
   * @throws ExchangeUnavailableException if Redis cannot count
   */
  public long countWrite(@NotNull String clientId, @NotNull String member) {
    String key =
        PREFIX + clientId + ":" + member + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        throw new ExchangeUnavailableException("The write quota cannot be counted.", null);
      }
      if (count == 1L) {
        redisTemplate.expire(key, TTL);
        budget.record(clientId, member, key, key.length() + (long) VALUE_BYTES, TTL);
      }
      return count;
    } catch (ExchangeUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Exchange quota count failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The write quota cannot be counted.", e);
    }
  }

  /**
   * Returns the seconds until the next UTC day, when every daily counter starts over.
   *
   * @return at least one
   */
  public long secondsUntilTomorrow() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    long tomorrow = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    return Math.max(1L, tomorrow - clock.instant().getEpochSecond());
  }
}
