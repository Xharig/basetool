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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The backend's own check of an exchange request, used as {@code @exchangeGate} in {@code
 * PreAuthorize} on every exchange controller method (REQ-XCH-004): the caller is an acting member
 * relayed for a registry client, the global switch is on, the client is active, the member has not
 * disconnected the installation or, after the token was issued, the client (REQ-XCH-008), and the
 * needed capability was both relayed and granted in the registry. Every refusal is counted.
 */
@Component("exchangeGate")
@RequiredArgsConstructor
public class ExchangeGate {

  /** Refusal reason: the caller is not an acting member relayed for an external client. */
  static final String REASON_NOT_RELAYED = "not_relayed";

  /** Refusal reason: the global exchange switch is off. */
  static final String REASON_SWITCH_OFF = "switch_off";

  /** Refusal reason: the relayed client is not in the registry. */
  static final String REASON_CLIENT_UNKNOWN = "client_unknown";

  /** Refusal reason: the relayed client is suspended. */
  static final String REASON_CLIENT_SUSPENDED = "client_suspended";

  /** Refusal reason: the needed capability was not relayed or not granted to the client. */
  static final String REASON_SCOPE_MISSING = "scope_missing";

  /** Refusal reason: the member disconnected the calling installation. */
  static final String REASON_INSTALLATION_REVOKED = "installation_revoked";

  /** Refusal reason: the member disconnected the client after the relayed token was issued. */
  static final String REASON_CLIENT_REVOKED = "client_revoked";

  /** Refusal reason: the mirrored client revocations could not be read, so none is assumed. */
  static final String REASON_REVOCATIONS_UNREADABLE = "revocations_unreadable";

  private final ExchangeClientRepository clientRepository;
  private final ExchangeSettingsRepository settingsRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeRevocationMirror revocationMirror;
  private final MeterRegistry meterRegistry;

  /** Registers the refusal counter for every reason at zero, so a first refusal is an increase. */
  @PostConstruct
  void registerRefusalCounters() {
    for (String reason :
        new String[] {
          REASON_NOT_RELAYED,
          REASON_SWITCH_OFF,
          REASON_CLIENT_UNKNOWN,
          REASON_CLIENT_SUSPENDED,
          REASON_SCOPE_MISSING,
          REASON_INSTALLATION_REVOKED,
          REASON_CLIENT_REVOKED,
          REASON_REVOCATIONS_UNREADABLE
        }) {
      meterRegistry.counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason);
    }
  }

  /**
   * Allows a request that needs one capability.
   *
   * @param scope the capability's OAuth scope
   * @param authentication the current authentication
   * @return {@code true} when every condition holds
   */
  @Transactional(readOnly = true)
  public boolean allows(@NotNull String scope, @Nullable Authentication authentication) {
    Optional<Set<String>> usable = usableScopes(authentication);
    if (usable.isEmpty()) {
      return false;
    }
    if (!usable.get().contains(scope)) {
      return refuse(REASON_SCOPE_MISSING);
    }
    return true;
  }

  /**
   * Allows a request that any exchange capability serves.
   *
   * @param authentication the current authentication
   * @return {@code true} when every condition holds for at least one capability
   */
  @Transactional(readOnly = true)
  public boolean allowsAny(@Nullable Authentication authentication) {
    Optional<Set<String>> usable = usableScopes(authentication);
    if (usable.isEmpty()) {
      return false;
    }
    if (usable.get().isEmpty()) {
      return refuse(REASON_SCOPE_MISSING);
    }
    return true;
  }

  /**
   * Returns the scopes both relayed and granted, after the relay, switch and client checks.
   *
   * @param authentication the current authentication
   * @return the usable scopes, or empty after a counted refusal
   */
  @NotNull
  private Optional<Set<String>> usableScopes(@Nullable Authentication authentication) {
    if (!(authentication instanceof SubjectAuthentication subject)
        || subject.externalClient() == null
        || authentication.getAuthorities().stream()
            .noneMatch(a -> Roles.authority(Roles.EXCHANGE_MEMBER).equals(a.getAuthority()))) {
      refuse(REASON_NOT_RELAYED);
      return Optional.empty();
    }
    boolean enabled =
        settingsRepository
            .findById(ExchangeSettings.SINGLETON_ID)
            .map(ExchangeSettings::isEnabled)
            .orElse(false);
    if (!enabled) {
      refuse(REASON_SWITCH_OFF);
      return Optional.empty();
    }
    Optional<ExchangeClient> client =
        clientRepository.findWithCapabilitiesByClientId(subject.externalClient());
    if (client.isEmpty()) {
      refuse(REASON_CLIENT_UNKNOWN);
      return Optional.empty();
    }
    if (client.get().getStatus() != ExchangeClientStatus.ACTIVE) {
      refuse(REASON_CLIENT_SUSPENDED);
      return Optional.empty();
    }
    if (installationRevoked(subject)) {
      refuse(REASON_INSTALLATION_REVOKED);
      return Optional.empty();
    }
    if (clientRevoked(subject)) {
      refuse(REASON_CLIENT_REVOKED);
      return Optional.empty();
    }
    Set<String> granted =
        client.get().getCapabilities().stream()
            .map(ExchangeCapability::getScope)
            .collect(Collectors.toSet());
    Set<String> relayed =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(Roles.EXCHANGE_CAPABILITY_PREFIX))
            .map(a -> a.substring(Roles.EXCHANGE_CAPABILITY_PREFIX.length()))
            .filter(granted::contains)
            .collect(Collectors.toSet());
    return Optional.of(relayed);
  }

  /**
   * Tells whether the calling installation was disconnected by the member.
   *
   * @param subject the acting member's authentication
   * @return {@code true} when the installation is known and revoked
   */
  private boolean installationRevoked(@NotNull SubjectAuthentication subject) {
    String key = subject.exchangeInstallationKey();
    if (key == null) {
      return false;
    }
    return installationRepository
        .findByKey(subject.externalClient(), UUID.fromString(subject.subject()), key)
        .map(ExchangeInstallation::getRevokedAt)
        .isPresent();
  }

  /**
   * Tells whether the member disconnected the client at or after the second the relayed token was
   * issued, reading the mirror the gateway reads (REQ-XCH-008); a request relayed without an issue
   * time counts as issued before any disconnect.
   *
   * @param subject the acting member's authentication, relayed for an external client
   * @return {@code true} when a disconnect covers the token
   * @throws ExternalServiceException when the mirror cannot be read, so the request fails closed
   */
  private boolean clientRevoked(@NotNull SubjectAuthentication subject) {
    Instant revokedAt;
    try {
      revokedAt =
          revocationMirror.revokedAt(
              Objects.requireNonNull(subject.externalClient()), UUID.fromString(subject.subject()));
    } catch (RuntimeException e) {
      refuse(REASON_REVOCATIONS_UNREADABLE);
      throw new ExternalServiceException("The exchange revocations could not be read", e);
    }
    if (revokedAt == null) {
      return false;
    }
    Long issuedAt = subject.exchangeTokenIssuedAt();
    return issuedAt == null || issuedAt <= revokedAt.getEpochSecond();
  }

  /**
   * Counts a refusal.
   *
   * @param reason the bounded reason
   * @return {@code false}
   */
  private boolean refuse(@NotNull String reason) {
    meterRegistry
        .counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason)
        .increment();
    return false;
  }
}
