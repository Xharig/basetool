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

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway under the {@code prod} profile while the legacy extractor endpoints
 * answer and the client-id allowlist is empty or only audits (REQ-INGEST-011): the {@code azp}
 * check would then admit any realm token that passes the audience check, an exchange client's
 * included, to relays that run with the member's stored authorities.
 */
@Component
@RequiredArgsConstructor
public class LegacyClientGateGuard implements InitializingBean {

  /** The environment whose active profiles decide whether the guard applies. */
  private final @NotNull Environment environment;

  /** The client-identity gate whose {@code azp} allowlist must enforce in production. */
  private final @NotNull ClientIdentityProperties clientIdentityProperties;

  /** Says whether the legacy endpoints still answer. */
  private final @NotNull IngestProperties ingestProperties;

  /**
   * Checks the posture once the configuration is bound.
   *
   * @throws IllegalStateException when production would run the legacy endpoints without an
   *     enforcing allowlist
   */
  @Override
  public void afterPropertiesSet() {
    check();
  }

  /**
   * Refuses an empty or audit-only allowlist under {@code prod} while the legacy endpoints are on.
   *
   * @throws IllegalStateException when production would run the legacy endpoints without an
   *     enforcing allowlist
   */
  void check() {
    if (!environment.matchesProfiles("prod") || !ingestProperties.legacyEndpoints().enabled()) {
      return;
    }
    if (clientIdentityProperties.auditOnly()) {
      throw new IllegalStateException(
          "The legacy ingest endpoints are on, but app.ingest.client-identity.audit-only is true;"
              + " set IRI_INGEST_CLIENT_AUDIT_ONLY=false or switch the legacy endpoints off"
              + " (REQ-INGEST-011).");
    }
    if (clientIdentityProperties.allowedClientIds().isEmpty()) {
      throw new IllegalStateException(
          "The legacy ingest endpoints are on, but app.ingest.client-identity.allowed-client-ids"
              + " is empty; set IRI_INGEST_ALLOWED_CLIENT_IDS or switch the legacy endpoints off"
              + " (REQ-INGEST-011).");
    }
  }
}
