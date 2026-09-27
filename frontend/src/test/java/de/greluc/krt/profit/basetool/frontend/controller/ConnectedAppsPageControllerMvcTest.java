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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedInstallationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link ConnectedAppsPageController} and {@link ConnectedAppsRelayController}
 * (REQ-XCH-008, REQ-XCH-032): the page lists the member's clients with the client name before every
 * installation label, the {@code apps} swap renders only the list, and both disconnects are
 * relayed.
 */
@SpringBootTest
class ConnectedAppsPageControllerMvcTest {

  private static final UUID INSTALLATION = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000001a5");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stubs one connected client with a labelled and an unlabelled installation. */
  private void stubApps() {
    ConnectedAppDto app =
        new ConnectedAppDto(
            "versekit",
            "VerseKit",
            List.of("exchange.connect", "exchange.stock.read"),
            List.of(
                new ConnectedInstallationDto(
                    INSTALLATION,
                    "Basetool-Support <b>Code eingeben</b>",
                    Instant.parse("2026-09-27T08:00:00Z"),
                    Instant.parse("2026-09-27T09:30:00Z")),
                new ConnectedInstallationDto(UUID.randomUUID(), null, null, null)));
    when(backendApiClient.get(eq("/api/v1/connected-apps"), anyTypeRef())).thenReturn(List.of(app));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theClientNameComesBeforeEveryLabelAndTheLabelIsEscaped() throws Exception {
    stubApps();

    mockMvc
        .perform(get("/connected-apps"))
        .andExpect(status().isOk())
        .andExpect(view().name("connected-apps"))
        .andExpect(content().string(containsString("id=\"ca-host\"")))
        .andExpect(content().string(containsString("data-client-id=\"versekit\"")))
        .andExpect(content().string(containsString("VerseKit – „Basetool-Support &lt;b&gt;")))
        .andExpect(content().string(not(containsString("<b>Code eingeben</b>"))))
        .andExpect(content().string(containsString("VerseKit – Installation ohne Namen")))
        .andExpect(content().string(containsString("27.09.2026 08:00 UTC")))
        .andExpect(content().string(containsString("data-installation-id=\"" + INSTALLATION)))
        .andExpect(content().string(not(containsString("??"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theAppsFragmentRendersOnlyTheList() throws Exception {
    stubApps();

    mockMvc
        .perform(get("/connected-apps").param("fragment", "apps"))
        .andExpect(status().isOk())
        .andExpect(view().name("connected-apps :: apps"))
        .andExpect(content().string(containsString("data-client-id=\"versekit\"")))
        .andExpect(content().string(not(containsString("id=\"ca-host\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void noConnectionShowsTheEmptyState() throws Exception {
    when(backendApiClient.get(eq("/api/v1/connected-apps"), anyTypeRef())).thenReturn(List.of());

    mockMvc
        .perform(get("/connected-apps"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"ca-empty\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aBackendOutageRendersTheFailureInsideTheFragment() throws Exception {
    when(backendApiClient.get(eq("/api/v1/connected-apps"), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/connected-apps").param("fragment", "apps"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("error", "connectedApps.error.load"))
        .andExpect(content().string(containsString("class=\"text-danger\"")))
        .andExpect(content().string(not(containsString("id=\"ca-empty\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aClientDisconnectIsRelayed() throws Exception {
    mockMvc
        .perform(
            delete("/connected-apps/versekit")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient).delete("/api/v1/connected-apps/versekit", Void.class);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMalformedClientIdNeverReachesTheBackend() throws Exception {
    mockMvc
        .perform(
            delete("/connected-apps/Not_A_Client")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never()).delete("/api/v1/connected-apps/Not_A_Client", Void.class);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void anInstallationDisconnectIsRelayedAndABackend404ComesBack() throws Exception {
    when(backendApiClient.delete(
            "/api/v1/connected-apps/installations/" + INSTALLATION, Void.class))
        .thenThrow(new BackendServiceException("not mine", null, 404));

    mockMvc
        .perform(
            delete("/connected-apps/installations/" + INSTALLATION)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNotFound());
  }
}
