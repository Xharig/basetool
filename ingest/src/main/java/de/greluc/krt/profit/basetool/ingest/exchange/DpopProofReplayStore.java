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

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;

/**
 * The DPoP {@code jti} replay cache of one path scope, partitioned by member (REQ-XCH-006,
 * REQ-XCH-023): every member may hold at most {@code maxPerMember} live proofs, so one member
 * cannot fill the store and lock the others out, and the whole store holds at most {@code
 * maxTotal}. A proof is kept until its {@code iat} plus the clock skew, as Spring's own cache does,
 * and a refused proof is counted on {@code basetool_ingest_dpop_replay_refused_total}.
 */
public final class DpopProofReplayStore {

  /** How often expired proofs are swept at the latest. */
  static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(10);

  /** The prefix of the partition of a token without a subject: the proof's key. */
  static final String KEY_PARTITION_PREFIX = "key:";

  /** Live proofs by the SHA-256 of their {@code jti}. */
  private final ConcurrentMap<String, Entry> proofs = new ConcurrentHashMap<>();

  /** Live proofs per member partition. */
  private final ConcurrentMap<String, Integer> perMember = new ConcurrentHashMap<>();

  /** Whether a sweep is running. */
  private final AtomicBoolean cleaning = new AtomicBoolean(false);

  /** When the last sweep finished, in epoch milliseconds. */
  private final AtomicLong lastCleanup;

  /** The most live proofs one member may hold. */
  private final int maxPerMember;

  /** The most live proofs the store holds. */
  private final int maxTotal;

  /** The time source. */
  private final @NotNull Clock clock;

  /** Counts a replayed proof. */
  private final @NotNull Counter replayed;

  /** Counts a proof refused because its member holds too many. */
  private final @NotNull Counter memberCap;

  /** Counts a proof refused because the store is full. */
  private final @NotNull Counter full;

  /**
   * Creates the store of one path scope.
   *
   * @param pathScope the {@code path_scope} label, {@code exchange} or {@code legacy}
   * @param maxPerMember the most live proofs one member may hold
   * @param maxTotal the most live proofs the store holds
   * @param meterRegistry where the refusals are counted, registered at zero
   * @param clock the time source
   */
  public DpopProofReplayStore(
      @NotNull String pathScope,
      int maxPerMember,
      int maxTotal,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    if (maxPerMember < 1 || maxTotal < maxPerMember) {
      throw new IllegalArgumentException("need 1 <= maxPerMember <= maxTotal");
    }
    this.maxPerMember = maxPerMember;
    this.maxTotal = maxTotal;
    this.clock = clock;
    this.lastCleanup = new AtomicLong(clock.millis());
    this.replayed = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_REPLAYED);
    this.memberCap = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_MEMBER_CAP);
    this.full = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_FULL);
  }

  /**
   * The member's view of the store, for Spring's {@link DPoPProofReplayValidator}.
   *
   * @param member the access token's subject; a token without one is partitioned by its proof key
   * @return a cache whose {@code putIfAbsent} claims a proof for that member
   */
  public @NotNull Cache forMember(@Nullable String member) {
    return new MemberView(member == null || member.isBlank() ? "" : member);
  }

  /**
   * Returns how many live proofs the store holds.
   *
   * @return the number of live proofs, expired ones not yet swept included
   */
  int size() {
    return proofs.size();
  }

  /**
   * Claims a proof for a member.
   *
   * @param jtiHash the SHA-256 of the proof's {@code jti}
   * @param expiresAt when the proof may be forgotten
   * @param partition the member partition
   * @return {@code true} when the proof is new and was stored; {@code false} when it was used
   *     before, its member holds too many or the store is full
   */
  boolean claim(@NotNull String jtiHash, @NotNull Instant expiresAt, @NotNull String partition) {
    cleanupIfDue();
    if (proofs.containsKey(jtiHash)) {
      replayed.increment();
      return false;
    }
    if (proofs.size() >= maxTotal) {
      cleanup();
      if (proofs.size() >= maxTotal) {
        full.increment();
        return false;
      }
    }
    AtomicBoolean capped = new AtomicBoolean(false);
    perMember.compute(
        partition,
        (k, v) -> {
          if (v != null && v >= maxPerMember) {
            capped.set(true);
            return v;
          }
          return v == null ? 1 : v + 1;
        });
    if (capped.get()) {
      memberCap.increment();
      return false;
    }
    if (proofs.putIfAbsent(jtiHash, new Entry(expiresAt, partition)) != null) {
      release(partition);
      replayed.increment();
      return false;
    }
    return true;
  }

  /** Sweeps expired proofs when the last sweep is older than {@link #CLEANUP_INTERVAL}. */
  private void cleanupIfDue() {
    if (clock.millis() - lastCleanup.get() > CLEANUP_INTERVAL.toMillis()) {
      cleanup();
    }
  }

  /** Forgets every expired proof and lowers its member's count. */
  void cleanup() {
    if (!cleaning.compareAndSet(false, true)) {
      return;
    }
    try {
      Instant now = clock.instant();
      for (Map.Entry<String, Entry> proof : proofs.entrySet()) {
        if (now.isAfter(proof.getValue().expiresAt())
            && proofs.remove(proof.getKey(), proof.getValue())) {
          release(proof.getValue().partition());
        }
      }
      lastCleanup.set(clock.millis());
    } finally {
      cleaning.set(false);
    }
  }

  /**
   * Lowers a member's count by one, dropping the member at zero.
   *
   * @param partition the member partition
   */
  private void release(@NotNull String partition) {
    perMember.computeIfPresent(partition, (k, v) -> v > 1 ? v - 1 : null);
  }

  /**
   * Registers one refusal reason at zero.
   *
   * @param registry the meter registry
   * @param pathScope the path scope label
   * @param reason the refusal reason
   * @return the counter
   */
  private static @NotNull Counter counter(
      @NotNull MeterRegistry registry, @NotNull String pathScope, @NotNull String reason) {
    return registry.counter(
        MetricNames.DPOP_REPLAY_REFUSED,
        MetricNames.TAG_PATH_SCOPE,
        pathScope,
        MetricNames.TAG_REASON,
        reason);
  }

  /**
   * A stored proof.
   *
   * @param expiresAt when the proof may be forgotten
   * @param partition the member partition that holds it
   */
  private record Entry(@NotNull Instant expiresAt, @NotNull String partition) {}

  /** One member's view of the store; the replay validator only calls {@code putIfAbsent}. */
  private final class MemberView implements Cache {

    /** The subject this view claims proofs for, or empty to partition by proof key. */
    private final @NotNull String member;

    /**
     * Creates the view.
     *
     * @param member the subject, or empty to partition by proof key
     */
    private MemberView(@NotNull String member) {
      this.member = member;
    }

    @Override
    public @NotNull String getName() {
      return "dpop-proof-replay";
    }

    @Override
    public @NotNull Object getNativeCache() {
      return proofs;
    }

    @Override
    public @Nullable ValueWrapper get(@NotNull Object key) {
      Entry entry = proofs.get(key);
      return entry == null ? null : new SimpleValueWrapper(entry);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void put(Object key, Object value) {
      putIfAbsent(key, value);
    }

    /**
     * Claims the proof for this view's member.
     *
     * @param key the SHA-256 of the proof's {@code jti}
     * @param value Spring's {@link DPoPProofReplayValidator.CacheValue} of the proof
     * @return {@code null} when the proof was stored, a wrapper of the value when it was refused
     */
    @Override
    public @Nullable ValueWrapper putIfAbsent(Object key, Object value) {
      if (!(key instanceof String jtiHash)
          || !(value instanceof DPoPProofReplayValidator.CacheValue proof)) {
        return new SimpleValueWrapper(value);
      }
      String partition =
          member.isEmpty() ? KEY_PARTITION_PREFIX + proof.getJwkThumbprint() : member;
      return claim(jtiHash, proof.getExpiresAt(), partition) ? null : new SimpleValueWrapper(value);
    }

    @Override
    public void evict(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException();
    }
  }
}
