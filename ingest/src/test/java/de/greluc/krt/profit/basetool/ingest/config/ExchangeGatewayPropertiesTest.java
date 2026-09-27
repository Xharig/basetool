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

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the bounds of the registry cache TTL, at most five seconds (REQ-XCH-003). */
class ExchangeGatewayPropertiesTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  private static ExchangeGatewayProperties properties(Duration ttl) {
    return new ExchangeGatewayProperties("exchange:registry", ttl, "https://docs.example/");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT1S", "PT5S"})
  void aCacheOfAtMostFiveSecondsPasses(String ttl) {
    assertThat(validator.validate(properties(Duration.parse(ttl)))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT5.001S", "PT1M", "PT24H", "PT-1S"})
  void aLongerOrNegativeCacheIsRefused(String ttl) {
    assertThat(validator.validate(properties(Duration.parse(ttl))))
        .singleElement()
        .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("registryCacheTtl"));
  }
}
