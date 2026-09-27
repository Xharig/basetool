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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Unit tests for {@link LegacyClientGateGuard}. */
class LegacyClientGateGuardTest {

  @Test
  void productionRefusesAnEmptyAllowlistWhileTheLegacyEndpointsAnswer() {
    LegacyClientGateGuard guard =
        new LegacyClientGateGuard(
            environment("prod"), TestProperties.clientIdentity(), TestProperties.ingest());

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IRI_INGEST_ALLOWED_CLIENT_IDS")
        .hasMessageNotContaining("basetool-sc-extractor");
  }

  @Test
  void productionStartsWithAnAllowlist() {
    LegacyClientGateGuard guard =
        new LegacyClientGateGuard(
            environment("prod"),
            TestProperties.clientIdentity("allowed-client-ids", "basetool-sc-extractor"),
            TestProperties.ingest());

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void productionRefusesAnAllowlistThatOnlyAudits() {
    LegacyClientGateGuard guard =
        new LegacyClientGateGuard(
            environment("prod"),
            TestProperties.clientIdentity(
                "allowed-client-ids", "basetool-sc-extractor", "audit-only", "true"),
            TestProperties.ingest());

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IRI_INGEST_CLIENT_AUDIT_ONLY");
  }

  @Test
  void auditOnlyIsAllowedOnceTheLegacyEndpointsAreOffAndOutsideProduction() {
    LegacyClientGateGuard off =
        new LegacyClientGateGuard(
            environment("prod"),
            TestProperties.clientIdentity("audit-only", "true"),
            TestProperties.ingest("legacy-endpoints.enabled", "false"));
    LegacyClientGateGuard dev =
        new LegacyClientGateGuard(
            environment("dev"),
            TestProperties.clientIdentity("audit-only", "true"),
            TestProperties.ingest());

    assertThatCode(off::afterPropertiesSet).doesNotThrowAnyException();
    assertThatCode(dev::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void productionStartsWithoutAnAllowlistOnceTheLegacyEndpointsAreOff() {
    LegacyClientGateGuard guard =
        new LegacyClientGateGuard(
            environment("prod"),
            TestProperties.clientIdentity(),
            TestProperties.ingest("legacy-endpoints.enabled", "false"));

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void devAndTestKeepTheInertGate() {
    for (String profile : new String[] {"dev", "test"}) {
      LegacyClientGateGuard guard =
          new LegacyClientGateGuard(
              environment(profile), TestProperties.clientIdentity(), TestProperties.ingest());

      assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }
  }

  /**
   * Builds an environment with one active profile.
   *
   * @param profile the profile
   * @return the environment
   */
  private static @NotNull MockEnvironment environment(@NotNull String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return environment;
  }
}
