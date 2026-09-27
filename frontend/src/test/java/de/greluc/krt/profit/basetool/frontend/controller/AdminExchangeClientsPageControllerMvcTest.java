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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link AdminExchangeClientsPageController} (REQ-XCH-003): the page and its {@code
 * registry} swap render the switch and the clients, and every write is relayed to the backend's
 * admin registry.
 */
@SpringBootTest
class AdminExchangeClientsPageControllerMvcTest {

  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-00000000c11e");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stubs one active client with an override and a contact, and the switch turned on. */
  private void stubRegistry() {
    ExchangeClientDto client =
        new ExchangeClientDto(
            ID,
            "versekit",
            "VerseKit",
            "ACTIVE",
            List.of("exchange.connect", "exchange.stock.read"),
            "2.1.0",
            "https://versekit.example/privacy",
            240,
            null,
            Instant.parse("2026-09-27T08:00:00Z"),
            null,
            3L);
    when(backendApiClient.get(eq("/api/v1/admin/exchange-clients"), anyTypeRef()))
        .thenReturn(List.of(client));
    when(backendApiClient.get("/api/v1/admin/exchange-settings", ExchangeSettingsDto.class))
        .thenReturn(new ExchangeSettingsDto(true, null, 5L));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageShowsTheSwitchTheClientAndEveryCapabilityToGrant() throws Exception {
    stubRegistry();

    mockMvc
        .perform(get("/admin/exchange-clients"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/exchange-clients"))
        .andExpect(
            model().attribute("capabilities", AdminExchangeClientsPageController.CAPABILITIES))
        .andExpect(model().attributeDoesNotExist("error"))
        .andExpect(content().string(containsString("id=\"xc-registry-host\"")))
        .andExpect(content().string(containsString("data-enabled=\"true\"")))
        .andExpect(content().string(containsString("data-version=\"5\"")))
        .andExpect(content().string(containsString(">VerseKit<")))
        .andExpect(content().string(containsString("data-version=\"3\"")))
        .andExpect(content().string(containsString("href=\"https://versekit.example/privacy\"")))
        .andExpect(content().string(containsString("value=\"exchange.drafts.refinery\"")))
        .andExpect(content().string(not(containsString("??exchange.capability"))))
        .andExpect(content().string(not(containsString("??admin.exchangeClients"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theRegistryFragmentRendersOnlyTheSwitchAndTheTable() throws Exception {
    stubRegistry();

    mockMvc
        .perform(get("/admin/exchange-clients").param("fragment", "registry"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/exchange-clients :: registry"))
        .andExpect(content().string(containsString("id=\"xc-table\"")))
        .andExpect(content().string(containsString("id=\"xc-switch\"")))
        .andExpect(content().string(not(containsString("id=\"xc-registry-host\""))))
        .andExpect(content().string(not(containsString("id=\"xc-form\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aBackendOutageRendersTheFailureInsideTheFragmentWithoutASwitch() throws Exception {
    when(backendApiClient.get(eq("/api/v1/admin/exchange-clients"), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/admin/exchange-clients").param("fragment", "registry"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("error", "admin.exchangeClients.error.load"))
        .andExpect(content().string(containsString("class=\"text-danger\"")))
        .andExpect(content().string(not(containsString("id=\"xc-switch\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aRegistrationIsRelayedToTheBackend() throws Exception {
    when(backendApiClient.post(
            eq("/api/v1/admin/exchange-clients"), any(), eq(ExchangeClientDto.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/exchange-clients")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"clientId\":\"versekit\",\"displayName\":\"VerseKit\","
                        + "\"capabilities\":[\"exchange.connect\"],\"requestsPerMinute\":null}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient)
        .post(eq("/api/v1/admin/exchange-clients"), body.capture(), eq(ExchangeClientDto.class));
    assertThat(body.getValue())
        .isEqualTo(
            new ExchangeClientCreateRequest(
                "versekit", "VerseKit", List.of("exchange.connect"), null, null, null, null));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aSuspensionCarriesTheVersionToTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/admin/exchange-clients/" + ID + "/suspend")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3}"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .post(
            "/api/v1/admin/exchange-clients/" + ID + "/suspend",
            new ExchangeClientStatusRequest(3L),
            ExchangeClientDto.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theSwitchIsRelayedAndAConflictComesBackAsAConflict() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/admin/exchange-settings"),
            eq(new ExchangeSettingsUpdateRequest(false, 5L)),
            eq(ExchangeSettingsDto.class)))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            put("/admin/exchange-clients/settings")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":5}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void oneClientIsReturnedForTheEditForm() throws Exception {
    stubRegistry();
    when(backendApiClient.get("/api/v1/admin/exchange-clients/" + ID, ExchangeClientDto.class))
        .thenReturn(
            new ExchangeClientDto(
                ID,
                "versekit",
                "VerseKit",
                "SUSPENDED",
                List.of("exchange.connect"),
                null,
                null,
                null,
                null,
                null,
                null,
                4L));

    mockMvc
        .perform(get("/admin/exchange-clients/" + ID).header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"))
        .andExpect(jsonPath("$.version").value(4));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberIsRefused() throws Exception {
    mockMvc.perform(get("/admin/exchange-clients")).andExpect(status().isForbidden());
  }
}
