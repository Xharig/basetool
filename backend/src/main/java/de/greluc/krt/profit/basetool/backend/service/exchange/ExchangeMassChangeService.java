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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeBlueprintChangeSet;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeShipChangeSet;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.ChangeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Previews and applies a change set the ingest gateway staged because the mass-change guard held it
 * back, once the member has seen it in the browser (REQ-XCH-021). Before either it checks again
 * what the gateway checked when the batch arrived: the global switch, the client's status and
 * capability, and that neither the client nor the installation was disconnected since.
 */
@Service
@RequiredArgsConstructor
public class ExchangeMassChangeService {

  /**
   * How long before now a disconnect still refuses a staged batch: the gateway keeps a staged batch
   * at most this long, so a batch staged before the disconnect cannot be older.
   */
  static final Duration STAGING_REACH = Duration.ofMinutes(30);

  private static final String BLUEPRINTS = "blueprints";
  private static final String STOCK = "stock";

  private final ExchangeSettingsRepository settingsRepository;
  private final ExchangeClientRepository clientRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeBlueprintWriteService blueprintWriteService;
  private final ExchangeStockWriteService stockWriteService;
  private final ExchangeShipWriteService shipWriteService;
  private final AuditService auditService;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock = Clock.systemUTC();

  /**
   * Shows what a staged change set would do, writing nothing.
   *
   * @param member the member
   * @param request the staged change set
   * @return what it would apply
   * @throws NotFoundException when the client is not registered
   * @throws AccessDeniedException when the exchange is off, the client is suspended or lacks the
   *     write capability, or the client or installation was disconnected
   * @throws BadRequestException when the change set does not read as one of the resource
   */
  public @NotNull ConnectedAppMassChangeResultDto preview(
      @NotNull UUID member, @NotNull ConnectedAppMassChangeRequestDto request) {
    ExchangeClient client = admit(member, request);
    ExchangeCaller caller =
        new ExchangeCaller(member, request.clientId(), request.installationKey());
    ExchangeChangeResultDto result = run(caller, request, true);
    return toDto(client, request.resource(), result);
  }

  /**
   * Applies a staged change set the member confirmed in one transaction, recorded as the client's
   * own write and audited with it.
   *
   * @param member the member
   * @param request the staged change set
   * @return what it applied
   * @throws NotFoundException when the client is not registered
   * @throws AccessDeniedException when the exchange is off, the client is suspended or lacks the
   *     write capability, or the client or installation was disconnected
   * @throws BadRequestException when the change set does not read as one of the resource
   */
  public @NotNull ConnectedAppMassChangeResultDto confirm(
      @NotNull UUID member, @NotNull ConnectedAppMassChangeRequestDto request) {
    ExchangeClient client = admit(member, request);
    ExchangeCaller caller =
        new ExchangeCaller(member, request.clientId(), request.installationKey());
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    ExchangeChangeResultDto result =
        ChangeSource.asClient(
            request.clientId(),
            request.installationKey(),
            () ->
                transaction.execute(
                    status -> {
                      ExchangeChangeResultDto applied = run(caller, request, false);
                      auditService.record(
                          AuditEventType.EXCHANGE_MASS_CHANGE_CONFIRMED,
                          client.getId(),
                          client.getClientId(),
                          member,
                          AuditDetails.of("resource", request.resource())
                              .with("applied", applied.applied()));
                      return applied;
                    }));
    if (result == null) {
      throw new IllegalStateException("The confirmed change set returned no result");
    }
    counter(client.getClientId(), request.resource()).increment();
    return toDto(client, request.resource(), result);
  }

  /**
   * Checks again what the gateway checked when the batch arrived.
   *
   * @param member the member
   * @param request the staged change set
   * @return the client
   */
  private @NotNull ExchangeClient admit(
      @NotNull UUID member, @NotNull ConnectedAppMassChangeRequestDto request) {
    boolean enabled =
        settingsRepository
            .findById(ExchangeSettings.SINGLETON_ID)
            .map(ExchangeSettings::isEnabled)
            .orElse(false);
    if (!enabled) {
      throw new AccessDeniedException("The exchange is switched off");
    }
    ExchangeClient client =
        clientRepository
            .findWithCapabilitiesByClientId(request.clientId())
            .orElseThrow(() -> new NotFoundException("Client not found"));
    if (client.getStatus() != ExchangeClientStatus.ACTIVE
        || !client.getCapabilities().contains(capability(request.resource()))) {
      throw new AccessDeniedException("The client may not write this");
    }
    Instant reach = clock.instant().minus(STAGING_REACH);
    boolean clientRevoked =
        revocationRepository.findAllByUserId(member).stream()
            .anyMatch(r -> r.clientId().equals(request.clientId()) && r.revokedAt().isAfter(reach));
    boolean installationRevoked =
        installationRepository
            .findByKey(request.clientId(), member, request.installationKey())
            .map(installation -> installation.getRevokedAt() != null)
            .orElse(true);
    if (clientRevoked || installationRevoked) {
      throw new AccessDeniedException("The client or installation was disconnected");
    }
    return client;
  }

  /**
   * Reads the change set as its resource's and runs it.
   *
   * @param caller the client, installation and member
   * @param request the staged change set
   * @param dryRun whether to preview only
   * @return the result
   */
  private @NotNull ExchangeChangeResultDto run(
      @NotNull ExchangeCaller caller,
      @NotNull ConnectedAppMassChangeRequestDto request,
      boolean dryRun) {
    return switch (request.resource()) {
      case BLUEPRINTS -> {
        ExchangeBlueprintChangeSet read = read(request, ExchangeBlueprintChangeSet.class);
        ExchangeBlueprintChangeSet changeSet = new ExchangeBlueprintChangeSet(read.ops(), dryRun);
        yield dryRun
            ? blueprintWriteService.apply(caller, changeSet)
            : blueprintWriteService.applyConfirmed(caller, changeSet);
      }
      case STOCK -> {
        ExchangeStockChangeSet read = read(request, ExchangeStockChangeSet.class);
        ExchangeStockChangeSet changeSet = new ExchangeStockChangeSet(read.ops(), dryRun);
        yield dryRun
            ? stockWriteService.apply(caller, changeSet)
            : stockWriteService.applyConfirmed(caller, changeSet);
      }
      default -> {
        ExchangeShipChangeSet read = read(request, ExchangeShipChangeSet.class);
        ExchangeShipChangeSet changeSet = new ExchangeShipChangeSet(read.ops(), dryRun);
        yield dryRun
            ? shipWriteService.apply(caller, changeSet)
            : shipWriteService.applyConfirmed(caller, changeSet);
      }
    };
  }

  /**
   * Reads and validates a staged change set.
   *
   * @param request the staged change set
   * @param type the resource's change-set type
   * @param <T> that type
   * @return the change set
   * @throws BadRequestException when it does not read or validate
   */
  private <T> @NotNull T read(
      @NotNull ConnectedAppMassChangeRequestDto request, @NotNull Class<T> type) {
    T changeSet;
    try {
      changeSet = objectMapper.readValue(request.changeSet(), type);
    } catch (JacksonException e) {
      throw new BadRequestException("The staged change set does not read", e);
    }
    if (changeSet == null || !validator.validate(changeSet).isEmpty()) {
      throw new BadRequestException("The staged change set is not valid");
    }
    return changeSet;
  }

  /**
   * Names the write capability a resource needs.
   *
   * @param resource the resource
   * @return the capability
   */
  private static @NotNull ExchangeCapability capability(@NotNull String resource) {
    return switch (resource) {
      case BLUEPRINTS -> ExchangeCapability.BLUEPRINTS_WRITE;
      case STOCK -> ExchangeCapability.STOCK_WRITE;
      default -> ExchangeCapability.HANGAR_WRITE;
    };
  }

  /**
   * Maps a result for the page.
   *
   * @param client the client
   * @param resource the resource
   * @param result the result
   * @return the page's result
   */
  private static @NotNull ConnectedAppMassChangeResultDto toDto(
      @NotNull ExchangeClient client,
      @NotNull String resource,
      @NotNull ExchangeChangeResultDto result) {
    return new ConnectedAppMassChangeResultDto(
        client.getDisplayName(),
        resource,
        result.dryRun(),
        result.applied(),
        result.unchanged(),
        result.notApplied());
  }

  /**
   * Returns the confirmation counter of a client and resource.
   *
   * @param clientId the client, a registered one
   * @param resource {@code blueprints}, {@code stock} or {@code ships}
   * @return the counter
   */
  private @NotNull Counter counter(@NotNull String clientId, @NotNull String resource) {
    String tag =
        switch (resource) {
          case BLUEPRINTS -> "blueprint";
          case STOCK -> "stock";
          default -> "ship";
        };
    return meterRegistry.counter(
        MetricNames.EXCHANGE_MASS_CHANGES_CONFIRMED,
        MetricNames.TAG_CLIENT_ID,
        clientId,
        MetricNames.TAG_RESOURCE,
        tag);
  }
}
