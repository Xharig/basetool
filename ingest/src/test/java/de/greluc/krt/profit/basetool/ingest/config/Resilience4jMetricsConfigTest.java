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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRelay;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The gateway context publishes the Resilience4j meters its alerts and dashboards read: the state
 * of the extractor's and the exchange's circuit breakers, and the large-change-set bulkhead with
 * its configured four slots (REQ-XCH-023).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class Resilience4jMetricsConfigTest {

  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void bothRelayBreakersPublishTheirState() {
    for (String breaker : new String[] {"backend", ExchangeRelay.BREAKER}) {
      Gauge closed =
          meterRegistry
              .find("resilience4j.circuitbreaker.state")
              .tag("name", breaker)
              .tag("state", "closed")
              .gauge();

      assertThat(closed).as(breaker).isNotNull();
      assertThat(closed.value()).as(breaker).isEqualTo(1.0d);
    }
  }

  @Test
  void theLargeChangeSetBulkheadPublishesItsFourSlots() {
    Gauge max =
        meterRegistry
            .find("resilience4j.bulkhead.max.allowed.concurrent.calls")
            .tag("name", ExchangeRelay.LARGE_CHANGE_SETS)
            .gauge();
    Gauge available =
        meterRegistry
            .find("resilience4j.bulkhead.available.concurrent.calls")
            .tag("name", ExchangeRelay.LARGE_CHANGE_SETS)
            .gauge();

    assertThat(max).isNotNull();
    assertThat(max.value()).isEqualTo(4.0d);
    assertThat(available).isNotNull();
    assertThat(available.value()).isEqualTo(4.0d);
  }
}
