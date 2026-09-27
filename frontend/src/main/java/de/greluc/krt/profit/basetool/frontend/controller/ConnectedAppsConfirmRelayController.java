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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The confirmation page's requests (REQ-XCH-021, ADR-0110): the script consumes the handoff once,
 * the change set then waits in the member's server session, and the browser only ever names it by
 * its handoff id.
 */
@Slf4j
@RestController
@RequestMapping("/connected-apps/confirm")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ConnectedAppsConfirmRelayController {

  /** The session attribute holding the consumed change sets by handoff id. */
  static final String SESSION_KEY = "connectedApps.massChanges";

  /** The backend's mass-change endpoints. */
  private static final String BACKEND = "/api/v1/connected-apps/mass-changes/";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final IngestHandoffService handoffService;
  private final BackendApiClient backendApiClient;

  /**
   * Consumes the staged change set, keeps it in the session and previews it.
   *
   * @param ref the handoff id
   * @param principal the member
   * @param session the member's session
   * @return the preview, {@code 404} when the handoff is unknown, expired or already consumed, or
   *     the relayed backend error
   */
  @PostMapping(value = "/load", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> load(
      @RequestBody @NotNull HandoffRef ref,
      @AuthenticationPrincipal @Nullable OidcUser principal,
      @NotNull HttpSession session) {
    Optional<StagedMassChange> staged =
        handoffService.consume(
            CurrentUser.userIdText(principal),
            ref.handoffId(),
            HandoffKind.MASS_CHANGE,
            StagedMassChange.class);
    if (staged.isEmpty() || staged.get().changeSet() == null) {
      return ResponseEntity.notFound().build();
    }
    ConnectedAppMassChangeRequestDto request =
        new ConnectedAppMassChangeRequestDto(
            staged.get().clientId(),
            staged.get().installationKey(),
            staged.get().resource(),
            staged.get().changeSet().toString());
    keep(session, ref.handoffId(), request);
    return relay(
        log,
        "preview held-back change set (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    BACKEND + "preview", request, ConnectedAppMassChangeResultDto.class)));
  }

  /**
   * Applies the change set kept for the handoff, once.
   *
   * @param ref the handoff id
   * @param session the member's session
   * @return what was applied, {@code 404} when nothing is kept for the id, or the relayed backend
   *     error
   */
  @PostMapping(value = "/apply", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> apply(
      @RequestBody @NotNull HandoffRef ref, @NotNull HttpSession session) {
    ConnectedAppMassChangeRequestDto request = take(session, ref.handoffId());
    if (request == null) {
      return ResponseEntity.notFound().build();
    }
    return relay(
        log,
        "confirm held-back change set (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    BACKEND + "confirm", request, ConnectedAppMassChangeResultDto.class)));
  }

  /**
   * Drops the change set kept for the handoff; nothing is written.
   *
   * @param ref the handoff id
   * @param session the member's session
   * @return {@code 204}
   */
  @PostMapping(value = "/discard", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Void> discard(
      @RequestBody @NotNull HandoffRef ref, @NotNull HttpSession session) {
    take(session, ref.handoffId());
    return ResponseEntity.noContent().build();
  }

  /**
   * Keeps a consumed change set in the session.
   *
   * @param session the session
   * @param handoffId the handoff id
   * @param request the change set
   */
  private static void keep(
      @NotNull HttpSession session,
      @NotNull String handoffId,
      @NotNull ConnectedAppMassChangeRequestDto request) {
    Map<String, String> kept = new HashMap<>(kept(session));
    kept.put(handoffId, MAPPER.writeValueAsString(request));
    session.setAttribute(SESSION_KEY, kept);
  }

  /**
   * Takes a kept change set out of the session.
   *
   * @param session the session
   * @param handoffId the handoff id
   * @return the change set, or {@code null} when none is kept for the id
   */
  private static @Nullable ConnectedAppMassChangeRequestDto take(
      @NotNull HttpSession session, @Nullable String handoffId) {
    if (handoffId == null) {
      return null;
    }
    Map<String, String> kept = new HashMap<>(kept(session));
    String json = kept.remove(handoffId);
    session.setAttribute(SESSION_KEY, kept);
    if (json == null) {
      return null;
    }
    try {
      return MAPPER.readValue(json, ConnectedAppMassChangeRequestDto.class);
    } catch (JacksonException ignored) {
      return null;
    }
  }

  /**
   * Reads the kept change sets.
   *
   * @param session the session
   * @return the JSON of each kept change set by handoff id
   */
  @SuppressWarnings("unchecked")
  private static @NotNull Map<String, String> kept(@NotNull HttpSession session) {
    return session.getAttribute(SESSION_KEY) instanceof Map<?, ?> map
        ? (Map<String, String>) map
        : Map.of();
  }

  /**
   * A handoff id the page's script sends.
   *
   * @param handoffId the id
   */
  public record HandoffRef(@Nullable String handoffId) {}

  /**
   * A change set the gateway staged, as it stores it.
   *
   * @param clientId the client that sent it
   * @param installationKey the installation that sent it
   * @param resource {@code blueprints}, {@code stock} or {@code ships}
   * @param changeSet the change set as the client sent it
   */
  public record StagedMassChange(
      @Nullable String clientId,
      @Nullable String installationKey,
      @Nullable String resource,
      @Nullable JsonNode changeSet) {}
}
