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

import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The hard byte budget of the exchange data the gateway writes to Redis: per client and member, per
 * client and in total (REQ-XCH-023, ADR-0221). Every stored value registers its size and expiry in
 * a sorted set per scope, as {@code <key>|<bytes>} scored by its expiry; expired entries are pruned
 * before each check, so the count goes down when keys expire, which a plain counter would not.
 */
@Slf4j
@Component
public class ExchangeBudget {

  /** The key prefix of the budget sets. */
  static final String PREFIX = "ingest:xch:budget:";

  /** A budget set outlives every entry it counts; each write moves its expiry this far out. */
  static final Duration SET_TTL = Duration.ofDays(3);

  private final StringRedisTemplate redisTemplate;
  private final ExchangeStoreProperties properties;
  private final Clock clock;
  private final AtomicLong totalUsed = new AtomicLong();

  /**
   * Creates the budget on the system clock and registers its gauge.
   *
   * @param redisTemplate the Redis access
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   */
  @Autowired
  public ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry) {
    this(redisTemplate, properties, meterRegistry, Clock.systemUTC());
  }

  /**
   * Creates the budget on the given clock.
   *
   * @param redisTemplate the Redis access
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   * @param clock the time source
   */
  ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.clock = clock;
    Gauge.builder(
            MetricNames.EXCHANGE_BUDGET_USED_RATIO,
            totalUsed,
            used -> (double) used.get() / properties.totalBytes())
        .description("The share of the exchange's total Redis byte budget in use, last measured.")
        .register(meterRegistry);
  }

  /**
   * Whether a value of the given size still fits every scope of a client and member.
   *
   * @param clientId the client
   * @param member the member
   * @param bytes the value's size
   * @return {@code true} when it fits all three budgets
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  public boolean fits(@NotNull String clientId, @NotNull String member, long bytes) {
    try {
      long now = clock.millis();
      long memberUsed = used(memberScope(clientId, member), now);
      long clientUsed = used(clientScope(clientId), now);
      long total = used(totalScope(), now);
      totalUsed.set(total);
      return memberUsed + bytes <= properties.memberBytes()
          && clientUsed + bytes <= properties.clientBytes()
          && total + bytes <= properties.totalBytes();
    } catch (RuntimeException e) {
      log.warn("Exchange budget read failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The exchange budget cannot be read.", e);
    }
  }

  /**
   * Registers a stored value in every scope of its client and member.
   *
   * @param clientId the client
   * @param member the member
   * @param key the value's Redis key
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @throws ExchangeUnavailableException if Redis cannot be written
   */
  public void record(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    long expiry = clock.millis() + ttl.toMillis();
    String entry = key + "|" + bytes;
    try {
      for (String scope :
          List.of(memberScope(clientId, member), clientScope(clientId), totalScope())) {
        redisTemplate.opsForZSet().add(scope, entry, expiry);
        redisTemplate.expire(scope, ttl.compareTo(SET_TTL) > 0 ? ttl : SET_TTL);
      }
      totalUsed.addAndGet(bytes);
    } catch (RuntimeException e) {
      log.warn("Exchange budget write failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The exchange budget cannot be written.", e);
    }
  }

  /**
   * Prunes the expired entries of one scope and sums the rest.
   *
   * @param scope the scope's sorted set
   * @param now the current epoch millisecond
   * @return the bytes in use
   */
  private long used(@NotNull String scope, long now) {
    redisTemplate.opsForZSet().removeRangeByScore(scope, Double.NEGATIVE_INFINITY, now);
    Set<String> entries =
        redisTemplate.opsForZSet().rangeByScore(scope, now, Double.POSITIVE_INFINITY);
    long used = 0L;
    if (entries != null) {
      for (String entry : entries) {
        int bar = entry.lastIndexOf('|');
        try {
          used += bar < 0 ? 0L : Long.parseLong(entry.substring(bar + 1));
        } catch (NumberFormatException ignored) {
          used += 0L;
        }
      }
    }
    return used;
  }

  /**
   * Returns the scope of a client and member.
   *
   * @param clientId the client
   * @param member the member
   * @return the sorted set's key
   */
  static @NotNull String memberScope(@NotNull String clientId, @NotNull String member) {
    return PREFIX + "m:" + clientId + ":" + member;
  }

  /**
   * Returns the scope of a client.
   *
   * @param clientId the client
   * @return the sorted set's key
   */
  static @NotNull String clientScope(@NotNull String clientId) {
    return PREFIX + "c:" + clientId;
  }

  /**
   * Returns the scope of the whole exchange.
   *
   * @return the sorted set's key
   */
  static @NotNull String totalScope() {
    return PREFIX + "all";
  }
}
