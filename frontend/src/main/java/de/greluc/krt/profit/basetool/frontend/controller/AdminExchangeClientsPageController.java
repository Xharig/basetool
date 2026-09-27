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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin page for the exchange client registry and the global exchange switch (REQ-XCH-003); its
 * writes go through {@link AdminExchangeClientsRelayController}, after which the registry section
 * is re-fetched in place (REQ-FE-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/exchange-clients")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminExchangeClientsPageController {

  private static final ParameterizedTypeReference<List<ExchangeClientDto>> LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** The {@code fragment} value that renders only the registry section, for the in-place swap. */
  static final String REGISTRY_FRAGMENT = "registry";

  /**
   * Every capability the registry can grant, in the backend's declaration order, labelled via
   * {@code exchange.capability.<scope>}.
   */
  static final List<String> CAPABILITIES =
      List.of(
          "exchange.connect",
          "exchange.blueprints.read",
          "exchange.blueprints.write",
          "exchange.stock.read",
          "exchange.stock.write",
          "exchange.hangar.read",
          "exchange.hangar.write",
          "exchange.demand.read",
          "exchange.drafts.blueprints",
          "exchange.drafts.refinery");

  /** Talks to the backend. */
  private final BackendApiClient backendApiClient;

  /**
   * Renders the registry page, or with {@code fragment=registry} only its registry section. A
   * failed load renders an empty list with {@code error} set and no switch.
   *
   * @param fragment {@code registry} for the section fragment; anything else renders the page
   * @param model receives {@code clients}, {@code settings}, {@code capabilities} and, on a failed
   *     load, {@code error}
   * @return {@code admin/exchange-clients}, or its {@code registry} fragment
   */
  @NotNull
  @GetMapping
  public String page(@Nullable @RequestParam(required = false) String fragment, Model model) {
    try {
      List<ExchangeClientDto> clients =
          backendApiClient.get(AdminExchangeClientsRelayController.CLIENTS, LIST_TYPE);
      model.addAttribute("clients", clients == null ? List.of() : clients);
      model.addAttribute(
          "settings",
          backendApiClient.get(
              AdminExchangeClientsRelayController.SETTINGS, ExchangeSettingsDto.class));
    } catch (Exception e) {
      log.debug("Failed to load the exchange registry", e);
      model.addAttribute("clients", List.of());
      model.addAttribute("settings", null);
      model.addAttribute("error", "admin.exchangeClients.error.load");
    }
    model.addAttribute("capabilities", CAPABILITIES);
    return REGISTRY_FRAGMENT.equals(fragment)
        ? "admin/exchange-clients :: " + REGISTRY_FRAGMENT
        : "admin/exchange-clients";
  }
}
