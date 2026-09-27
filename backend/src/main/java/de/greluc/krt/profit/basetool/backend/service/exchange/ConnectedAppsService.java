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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedAppDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedInstallationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeRevocationRow;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own view and control of the exchange connections (REQ-XCH-008, REQ-XCH-032): which
 * clients and installations are connected, and disconnecting one installation or a whole client. A
 * disconnect reaches the gateway's mirror before the commit or not at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectedAppsService {

  /** The {@code kind} label of a disconnected installation. */
  static final String KIND_INSTALLATION = "installation";

  /** The {@code kind} label of a disconnected client. */
  static final String KIND_CLIENT = "client";

  private final ExchangeClientRepository clientRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final ExchangeRevocationMirror revocationMirror;
  private final KeycloakService keycloakService;
  private final AuditService auditService;
  private final MeterRegistry meterRegistry;

  /** Reads and clears the new-connection notifications that mark an installation unseen. */
  private final NotificationRepository notificationRepository;

  private final Clock clock = Clock.systemUTC();

  /** Registers the disconnect counter for both kinds at zero. */
  @PostConstruct
  void registerDisconnectCounters() {
    meterRegistry.counter(MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, KIND_CLIENT);
    meterRegistry.counter(
        MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, KIND_INSTALLATION);
  }

  /**
   * Lists the member's connected clients with their live installations; an installation last seen
   * before its client was disconnected counts as gone.
   *
   * @param member the member
   * @return the connected clients, by client id
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<ConnectedAppDto> list(@NotNull UUID member) {
    Map<String, Instant> revokedAt =
        revocationRepository.findAllByUserId(member).stream()
            .collect(
                Collectors.toMap(
                    ExchangeRevocationRow::clientId, ExchangeRevocationRow::revokedAt));
    Map<String, List<ExchangeInstallation>> byClient = new LinkedHashMap<>();
    for (ExchangeInstallation installation : installationRepository.findAllByUserId(member)) {
      Instant cut = revokedAt.get(installation.getClient().getClientId());
      if (installation.getRevokedAt() != null
          || (cut != null && !installation.getLastSeenAt().isAfter(cut))) {
        continue;
      }
      byClient
          .computeIfAbsent(installation.getClient().getClientId(), k -> new ArrayList<>())
          .add(installation);
    }
    Set<UUID> unseen =
        Set.copyOf(
            notificationRepository.findUnreadEntityIds(
                member, NotificationType.EXCHANGE_INSTALLATION_CONNECTED));
    List<ConnectedAppDto> apps = new ArrayList<>();
    for (List<ExchangeInstallation> installations : byClient.values()) {
      ExchangeClient client = installations.getFirst().getClient();
      apps.add(
          new ConnectedAppDto(
              client.getClientId(),
              client.getDisplayName(),
              client.getCapabilities().stream().sorted().map(ExchangeCapability::getScope).toList(),
              installations.stream()
                  .map(
                      i ->
                          new ConnectedInstallationDto(
                              i.getId(),
                              i.getLabel(),
                              i.getFirstSeenAt(),
                              i.getLastSeenAt(),
                              unseen.contains(i.getId())))
                  .toList()));
    }
    apps.sort((a, b) -> a.clientId().compareTo(b.clientId()));
    return apps;
  }

  /**
   * Marks the member's new-connection notifications read, which ends the highlight of every
   * installation they announced; nothing else changes, so it is not audited.
   *
   * @param member the member
   * @return how many notifications were marked
   */
  @Transactional
  public int markSeen(@NotNull UUID member) {
    return notificationRepository.markReadOfType(
        member, NotificationType.EXCHANGE_INSTALLATION_CONNECTED, clock.instant());
  }

  /**
   * Disconnects one of the member's installations: its key is denied in the mirror, then marked
   * revoked, so every token bound to it is refused and reconnecting needs a new key.
   *
   * @param member the member
   * @param installationId the installation
   * @throws ExternalServiceException when the mirror could not be written
   */
  @Transactional
  public void disconnectInstallation(@NotNull UUID member, @NotNull UUID installationId) {
    ExchangeInstallation installation =
        Entities.require(
            installationRepository.findById(installationId).filter(i -> ownedBy(i, member)),
            "Installation not found");
    if (installation.getRevokedAt() != null) {
      return;
    }
    Instant now = clock.instant();
    mirror(() -> revocationMirror.deny(installation.getKeyThumbprint(), now));
    installation.setRevokedAt(now);
    installationRepository.saveAndFlush(installation);
    auditService.record(
        AuditEventType.EXCHANGE_INSTALLATION_DISCONNECTED,
        installation.getId(),
        installation.getClient().getClientId(),
        member,
        null);
    count(KIND_INSTALLATION);
  }

  /**
   * Disconnects a whole client for the member: the revocation time reaches the mirror, the member's
   * Keycloak consent for the client and its offline tokens are removed, and the time is stored, so
   * every token issued before it is refused and a new connection works at once.
   *
   * @param member the member
   * @param clientId the Keycloak client id
   * @throws ExternalServiceException when the mirror or Keycloak could not be reached
   */
  @Transactional
  public void disconnectClient(@NotNull UUID member, @NotNull String clientId) {
    ExchangeClient client =
        Entities.require(
            clientRepository.findWithCapabilitiesByClientId(clientId), "Exchange client not found");
    Instant now = clock.instant();
    mirror(() -> revocationMirror.revoke(clientId, member, now));
    revocationRepository.upsert(client.getId(), member, now);
    try {
      keycloakService.revokeConsent(member, clientId);
    } catch (RuntimeException e) {
      throw new ExternalServiceException("The client's consent could not be removed", e);
    }
    auditService.record(
        AuditEventType.EXCHANGE_CLIENT_DISCONNECTED,
        client.getId(),
        client.getClientId(),
        member,
        AuditDetails.of("by", "member"));
    count(KIND_CLIENT);
  }

  /**
   * Tells whether an installation belongs to the member.
   *
   * @param installation the installation
   * @param member the member
   * @return {@code true} when it is the member's
   */
  private static boolean ownedBy(@NotNull ExchangeInstallation installation, @NotNull UUID member) {
    return member.equals(installation.getUser().getId());
  }

  /**
   * Writes to the mirror before the commit, failing the action when it cannot.
   *
   * @param write the write
   * @throws ExternalServiceException when the write failed
   */
  private void mirror(@NotNull Runnable write) {
    try {
      write.run();
    } catch (RuntimeException e) {
      throw new ExternalServiceException("The exchange revocation could not be mirrored", e);
    }
  }

  /**
   * Counts one disconnect.
   *
   * @param kind {@code client} or {@code installation}
   */
  private void count(@NotNull String kind) {
    meterRegistry.counter(MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, kind).increment();
  }
}
