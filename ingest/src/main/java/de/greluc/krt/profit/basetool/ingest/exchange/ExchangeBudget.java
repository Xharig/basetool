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
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The hard byte budget of the exchange data the gateway writes to Redis: per client and member, per
 * client and in total (REQ-XCH-023, ADR-0221). Every stored value registers {@code <key>|<charge>}
 * in a sorted set per scope, scored by its expiry, and each scope keeps its running total beside
 * it. One Lua script prunes expired entries, checks all three limits and records the entry
 * atomically, so parallel writes cannot overshoot and no call reads a whole set.
 */
@Slf4j
@Component
public class ExchangeBudget {

  /** The key prefix of the budget sets. */
  static final String PREFIX = "ingest:xch:budget:";

  /** The key prefix of the running totals, one per budget set. */
  static final String SUM_PREFIX = "ingest:xch:budget-sum:";

  /** The key prefix of a reservation made before the value's own key is known. */
  public static final String PENDING_PREFIX = "ingest:xch:pending:";

  /** A budget set outlives every entry it counts; each call moves its expiry at least this far. */
  static final Duration SET_TTL = Duration.ofDays(3);

  /**
   * The bytes charged per entry on top of its value: its member in the three sets and the key's own
   * bookkeeping in Redis.
   */
  static final int ENTRY_OVERHEAD_BYTES = 512;

  /** The most expired entries one call prunes per set, so a call stays short. */
  static final int PRUNE_BATCH = 1000;

  /** Marks a limit that is not checked. */
  private static final String UNCHECKED = "-1";

  /** The check-and-record script; its result is the total in use, or {@code -1 - total}. */
  private static final RedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          """
          local release = ARGV[3]
          local add = ARGV[4]
          local expiry = ARGV[5]
          local ttl = ARGV[6]
          local function size(entry)
            return tonumber(string.match(entry, '|(%d+)$')) or 0
          end
          local function empty(set)
            return #redis.call('ZRANGE', set, 0, 0) == 0
          end
          local used = {}
          local present = {}
          for i = 1, 3 do
            local set = KEYS[i]
            local raw = redis.call('GET', KEYS[i + 3])
            local total = nil
            if raw then
              total = tonumber(raw)
            end
            if total == nil then
              total = 0
              for _, entry in ipairs(redis.call('ZRANGE', set, 0, -1)) do
                total = total + size(entry)
              end
            end
            local expired = redis.call('ZRANGEBYSCORE', set, '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            if #expired > 0 then
              for _, entry in ipairs(expired) do
                total = total - size(entry)
              end
              redis.call('ZREM', set, unpack(expired))
            end
            if empty(set) or total < 0 then
              total = 0
            end
            used[i] = total
            present[i] = release ~= '' and redis.call('ZSCORE', set, release) ~= false
          end
          local fits = 1
          if add ~= '' then
            local bytes = size(add)
            for i = 1, 3 do
              local limit = tonumber(ARGV[6 + i])
              local freed = present[i] and size(release) or 0
              if limit >= 0 and used[i] - freed + bytes > limit then
                fits = 0
              end
            end
          end
          if fits == 1 then
            for i = 1, 3 do
              if present[i] then
                redis.call('ZREM', KEYS[i], release)
                used[i] = math.max(0, used[i] - size(release))
              end
              if add ~= '' and redis.call('ZADD', KEYS[i], 'GT', expiry, add) == 1 then
                used[i] = used[i] + size(add)
              end
            end
          end
          for i = 1, 3 do
            if empty(KEYS[i]) then
              redis.call('DEL', KEYS[i + 3])
              used[i] = 0
            else
              redis.call('SET', KEYS[i + 3], string.format('%d', used[i]), 'KEEPTTL')
              for _, key in ipairs({KEYS[i], KEYS[i + 3]}) do
                redis.call('PEXPIRE', key, ttl, 'NX')
                redis.call('PEXPIRE', key, ttl, 'GT')
              end
            end
          end
          if fits == 1 then
            return used[3]
          end
          return -1 - used[3]
          """,
          Long.class);

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
   * Returns what an entry of a value's size is charged: the value plus {@link
   * #ENTRY_OVERHEAD_BYTES}.
   *
   * @param bytes the value's size
   * @return the bytes counted against every scope
   */
  public static long charge(long bytes) {
    return bytes + ENTRY_OVERHEAD_BYTES;
  }

  /**
   * Records a value in every scope of its client and member only if it fits all three budgets, as
   * one atomic step.
   *
   * @param clientId the client
   * @param member the member
   * @param key the value's Redis key, or a {@link #PENDING_PREFIX} name for a reservation
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @return {@code true} when it fitted and is recorded; {@code false} when nothing was recorded
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public boolean reserve(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    return run(clientId, member, "", entry(key, bytes), ttl, true);
  }

  /**
   * Replaces a reservation with the value it was made for, atomically; when the value does not fit
   * even with the reservation freed, the reservation stays and nothing else changes.
   *
   * @param clientId the client
   * @param member the member
   * @param reservedKey the reservation's key
   * @param reservedBytes the reservation's size
   * @param key the value's Redis key
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @return {@code true} when the value is recorded in place of the reservation
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public boolean settle(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String reservedKey,
      long reservedBytes,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    return run(clientId, member, entry(reservedKey, reservedBytes), entry(key, bytes), ttl, true);
  }

  /**
   * Records a value that already exists in every scope, without a limit check.
   *
   * @param clientId the client
   * @param member the member
   * @param key the value's Redis key
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public void record(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    run(clientId, member, "", entry(key, bytes), ttl, false);
  }

  /**
   * Removes an entry from every scope; a failure is logged, since the entry expires on its own.
   *
   * @param clientId the client
   * @param member the member
   * @param key the entry's key
   * @param bytes the entry's size
   */
  public void release(
      @NotNull String clientId, @NotNull String member, @NotNull String key, long bytes) {
    try {
      run(clientId, member, entry(key, bytes), "", SET_TTL, false);
    } catch (ExchangeUnavailableException e) {
      log.warn("An exchange budget entry could not be released");
    }
  }

  /**
   * Runs the script for one client and member and keeps the gauge current.
   *
   * @param clientId the client
   * @param member the member
   * @param release the entry to remove, or empty
   * @param add the entry to add, or empty
   * @param ttl the added entry's lifetime
   * @param checked whether the limits are checked
   * @return {@code true} when the step was applied
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  private boolean run(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String release,
      @NotNull String add,
      @NotNull Duration ttl,
      boolean checked) {
    long now = clock.millis();
    Duration setTtl = ttl.compareTo(SET_TTL) > 0 ? ttl : SET_TTL;
    List<String> sets = List.of(memberScope(clientId, member), clientScope(clientId), totalScope());
    List<String> keys =
        List.of(
            sets.get(0),
            sets.get(1),
            sets.get(2),
            sum(sets.get(0)),
            sum(sets.get(1)),
            sum(sets.get(2)));
    Long result;
    try {
      result =
          redisTemplate.execute(
              SCRIPT,
              keys,
              Long.toString(now),
              Integer.toString(PRUNE_BATCH),
              release,
              add,
              Long.toString(now + ttl.toMillis()),
              Long.toString(setTtl.toMillis()),
              checked ? Long.toString(properties.memberBytes()) : UNCHECKED,
              checked ? Long.toString(properties.clientBytes()) : UNCHECKED,
              checked ? Long.toString(properties.totalBytes()) : UNCHECKED);
    } catch (RuntimeException e) {
      log.warn("Exchange budget update failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The exchange budget cannot be reached.", e);
    }
    if (result == null) {
      throw new ExchangeUnavailableException("The exchange budget answered nothing.", null);
    }
    totalUsed.set(result >= 0 ? result : -1L - result);
    return result >= 0;
  }

  /**
   * Returns the set member of an entry.
   *
   * @param key the entry's key
   * @param bytes the value's size
   * @return {@code <key>|<charge>}
   */
  static @NotNull String entry(@NotNull String key, long bytes) {
    return key + "|" + charge(bytes);
  }

  /**
   * Returns the running total's key of a budget set.
   *
   * @param scope the budget set's key
   * @return the total's key
   */
  static @NotNull String sum(@NotNull String scope) {
    return SUM_PREFIX + scope.substring(PREFIX.length());
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
