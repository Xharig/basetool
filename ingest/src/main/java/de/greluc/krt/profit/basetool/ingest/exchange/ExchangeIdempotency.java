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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The exchange's idempotency cache in Redis (REQ-XCH-020): per client, member and key, the answer
 * to a write and the fingerprint of the request that produced it, kept for a day; and a lock while
 * the first request with a key is in flight. Keys are hashed, so no client-chosen text becomes part
 * of a Redis key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeIdempotency {

  /** The key prefix of a cached answer. */
  static final String PREFIX = "ingest:xch:idem:";

  /** The key prefix of a lock. */
  static final String LOCK_PREFIX = "ingest:xch:idem-lock:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ExchangeStoreProperties properties;

  /**
   * Returns the namespace of a key: client, member and the key's hash.
   *
   * @param clientId the client
   * @param member the member
   * @param idempotencyKey the client's key
   * @return the namespace
   */
  public static @NotNull String namespace(
      @NotNull String clientId, @NotNull String member, @NotNull String idempotencyKey) {
    return clientId + ":" + member + ":" + sha256(idempotencyKey.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns a request's fingerprint.
   *
   * @param method the method
   * @param path the path
   * @param body the body
   * @return the SHA-256 of method, path and body, hex
   */
  public static @NotNull String fingerprint(
      @NotNull String method, @NotNull String path, byte @NotNull [] body) {
    byte[] head = (method + " " + path + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[head.length + body.length];
    System.arraycopy(head, 0, all, 0, head.length);
    System.arraycopy(body, 0, all, head.length, body.length);
    return sha256(all);
  }

  /**
   * Finds the cached answer of a namespace.
   *
   * @param namespace the namespace
   * @return the answer, or empty
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  public @NotNull Optional<Stored> find(@NotNull String namespace) {
    String json;
    try {
      json = redisTemplate.opsForValue().get(PREFIX + namespace);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    if (json == null) {
      return Optional.empty();
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      JsonNode contentType = node.get("contentType");
      return Optional.of(
          new Stored(
              node.get("fingerprint").stringValue(),
              node.get("status").intValue(),
              contentType != null && contentType.isString() ? contentType.stringValue() : null,
              node.get("body").stringValue()));
    } catch (RuntimeException e) {
      log.warn(
          "An idempotency entry is unreadable and is ignored: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Takes the lock of a namespace for the first request in flight.
   *
   * @param namespace the namespace
   * @return {@code true} when this request holds the lock now
   * @throws ExchangeUnavailableException if Redis cannot be written
   */
  public boolean lock(@NotNull String namespace) {
    try {
      Boolean taken =
          redisTemplate
              .opsForValue()
              .setIfAbsent(LOCK_PREFIX + namespace, "1", properties.lockTtl());
      return Boolean.TRUE.equals(taken);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  /**
   * Releases the lock of a namespace; a failure is logged, since the lock expires on its own.
   *
   * @param namespace the namespace
   */
  public void unlock(@NotNull String namespace) {
    try {
      redisTemplate.delete(LOCK_PREFIX + namespace);
    } catch (RuntimeException e) {
      log.warn("An idempotency lock could not be released: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Caches an answer for its namespace.
   *
   * @param namespace the namespace
   * @param stored the answer
   * @return the size of the stored value in bytes
   * @throws ExchangeUnavailableException if Redis cannot be written
   */
  public int store(@NotNull String namespace, @NotNull Stored stored) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("fingerprint", stored.fingerprint());
    node.put("status", stored.status());
    node.put("contentType", stored.contentType());
    node.put("body", stored.body());
    String json = objectMapper.writeValueAsString(node);
    try {
      redisTemplate.opsForValue().set(PREFIX + namespace, json, properties.idempotencyTtl());
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    return json.getBytes(StandardCharsets.UTF_8).length + PREFIX.length() + namespace.length();
  }

  /**
   * Wraps a Redis failure.
   *
   * @param cause the failure
   * @return the exception to throw
   */
  private static @NotNull ExchangeUnavailableException unavailable(
      @NotNull RuntimeException cause) {
    log.warn("Exchange idempotency store failed: {}", cause.getClass().getSimpleName());
    return new ExchangeUnavailableException("The idempotency store cannot be reached.", cause);
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return the SHA-256, hex
   */
  private static @NotNull String sha256(byte @NotNull [] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * A cached answer.
   *
   * @param fingerprint the fingerprint of the request that produced it
   * @param status the status
   * @param contentType the content type
   * @param body the body
   */
  public record Stored(
      @NotNull String fingerprint,
      int status,
      @Nullable String contentType,
      @NotNull String body) {}
}
