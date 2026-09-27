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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The byte budget and the idempotency cache against a real Redis under the ingest ACL user
 * (REQ-XCH-020, REQ-XCH-023): filling one member's budget and then one client's leaves every other
 * member and client working.
 */
@Testcontainers
class ExchangeStoreRedisIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private static final ExchangeStoreProperties SMALL =
      new ExchangeStoreProperties(
          1024L, 3072L, 5120L, 1024, Duration.ofHours(24), Duration.ofMinutes(2));

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-27T12:00:00Z"));
  private LettuceConnectionFactory ingest;
  private StringRedisTemplate template;
  private ExchangeBudget budget;
  private ExchangeIdempotency idempotency;

  @BeforeEach
  void setUp() {
    ingest = connect(RedisAclTemplate.INGEST_USER, "REDIS_INGEST_PASSWORD");
    template = new StringRedisTemplate(ingest);
    template.afterPropertiesSet();
    template.delete(
        List.of(
            ExchangeBudget.memberScope("a", "m1"),
            ExchangeBudget.memberScope("a", "m2"),
            ExchangeBudget.memberScope("a", "m3"),
            ExchangeBudget.memberScope("b", "m1"),
            ExchangeBudget.clientScope("a"),
            ExchangeBudget.clientScope("b"),
            ExchangeBudget.totalScope()));
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of("UTC");
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    budget = new ExchangeBudget(template, SMALL, new SimpleMeterRegistry(), clock);
    idempotency = new ExchangeIdempotency(template, JsonMapper.builder().build(), SMALL);
  }

  @AfterEach
  void tearDown() {
    ingest.destroy();
  }

  @Test
  void aFullMemberBudgetStopsOnlyThatMember() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 900L, Duration.ofHours(1));

    assertThat(budget.fits("a", "m1", 200L)).isFalse();
    assertThat(budget.fits("a", "m2", 200L)).isTrue();
    assertThat(budget.fits("b", "m1", 200L)).isTrue();
  }

  @Test
  void aFullClientBudgetStopsOnlyThatClient() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 1000L, Duration.ofHours(1));
    budget.record("a", "m2", "ingest:xch:idem:a:m2:1", 1000L, Duration.ofHours(1));
    budget.record("a", "m3", "ingest:xch:idem:a:m3:1", 1000L, Duration.ofHours(1));

    assertThat(budget.fits("a", "m4", 200L)).isFalse();
    assertThat(budget.fits("b", "m1", 200L)).isTrue();
  }

  @Test
  void expiredEntriesFreeTheirBytes() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 1000L, Duration.ofMinutes(5));
    assertThat(budget.fits("a", "m1", 100L)).isFalse();

    now.set(now.get().plus(Duration.ofMinutes(6)));

    assertThat(budget.fits("a", "m1", 100L)).isTrue();
  }

  @Test
  void theCacheReplaysAndTheLockHoldsOneWriterUnderTheIngestUser() {
    String namespace = ExchangeIdempotency.namespace("a", "m1", "key-000001");

    assertThat(idempotency.find(namespace)).isEmpty();
    assertThat(idempotency.lock(namespace)).isTrue();
    assertThat(idempotency.lock(namespace)).isFalse();

    int size =
        idempotency.store(
            namespace, new ExchangeIdempotency.Stored("fp", 200, "application/json", "{\"ok\":1}"));
    idempotency.unlock(namespace);

    assertThat(size).isPositive();
    assertThat(idempotency.find(namespace))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.fingerprint()).isEqualTo("fp");
              assertThat(stored.status()).isEqualTo(200);
              assertThat(stored.body()).isEqualTo("{\"ok\":1}");
            });
    assertThat(idempotency.lock(namespace)).isTrue();
    LettuceConnectionFactory admin = connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD");
    try {
      StringRedisTemplate observer = new StringRedisTemplate(admin);
      observer.afterPropertiesSet();
      assertThat(observer.getExpire(ExchangeIdempotency.PREFIX + namespace)).isPositive();
    } finally {
      admin.destroy();
    }
  }

  /**
   * Connects as one ACL user.
   *
   * @param user the user
   * @param passwordVariable the variable naming its E2E password
   * @return the started factory
   */
  private static LettuceConnectionFactory connect(String user, String passwordVariable) {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    config.setUsername(user);
    config.setPassword(RedisAclTemplate.E2E_PASSWORDS.get(passwordVariable));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Returns the E2E passwords the ACL is rendered with.
   *
   * @return the values
   */
  private static Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
