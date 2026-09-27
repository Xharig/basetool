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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ExchangeGateTest {

  private final ExchangeClientRepository clientRepository = mock(ExchangeClientRepository.class);
  private final ExchangeSettingsRepository settingsRepository =
      mock(ExchangeSettingsRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExchangeGate gate =
      new ExchangeGate(clientRepository, settingsRepository, meterRegistry);

  private ExchangeClient client;
  private ExchangeSettings settings;

  @BeforeEach
  void setUp() {
    client = new ExchangeClient();
    client.setClientId("versekit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_READ));
    settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(settingsRepository.findById(ExchangeSettings.SINGLETON_ID))
        .thenReturn(Optional.of(settings));
    when(clientRepository.findWithCapabilitiesByClientId("versekit"))
        .thenReturn(Optional.of(client));
  }

  @Test
  void allowsARelayedAndGrantedCapability() {
    assertThat(gate.allows("exchange.stock.read", acting("versekit", "exchange.stock.read")))
        .isTrue();
    assertThat(gate.allowsAny(acting("versekit", "exchange.connect"))).isTrue();
  }

  @Test
  void refusesACapabilityThatWasRelayedButNotGranted() {
    assertThat(gate.allows("exchange.stock.write", acting("versekit", "exchange.stock.write")))
        .isFalse();
    assertThat(gate.allowsAny(acting("versekit", "exchange.stock.write"))).isFalse();
    assertThat(refused(ExchangeGate.REASON_SCOPE_MISSING)).isEqualTo(2);
  }

  @Test
  void refusesACapabilityThatWasGrantedButNotRelayed() {
    assertThat(gate.allows("exchange.stock.read", acting("versekit", "exchange.connect")))
        .isFalse();
  }

  @Test
  void refusesAnythingButARelayedActingMember() {
    assertThat(gate.allowsAny(null)).isFalse();
    assertThat(
            gate.allowsAny(
                new TestingAuthenticationToken("s", "c", "ROLE_EXCHANGE_MEMBER", "ROLE_ADMIN")))
        .isFalse();
    assertThat(gate.allowsAny(acting(null, "exchange.connect"))).isFalse();
    assertThat(refused(ExchangeGate.REASON_NOT_RELAYED)).isEqualTo(3);
  }

  @Test
  void refusesWhileTheSwitchIsOff() {
    settings.setEnabled(false);

    assertThat(gate.allowsAny(acting("versekit", "exchange.connect"))).isFalse();
    assertThat(refused(ExchangeGate.REASON_SWITCH_OFF)).isEqualTo(1);
  }

  @Test
  void refusesAnUnknownOrSuspendedClient() {
    when(clientRepository.findWithCapabilitiesByClientId("stranger")).thenReturn(Optional.empty());
    assertThat(gate.allowsAny(acting("stranger", "exchange.connect"))).isFalse();

    client.setStatus(ExchangeClientStatus.SUSPENDED);
    assertThat(gate.allowsAny(acting("versekit", "exchange.connect"))).isFalse();

    assertThat(refused(ExchangeGate.REASON_CLIENT_UNKNOWN)).isEqualTo(1);
    assertThat(refused(ExchangeGate.REASON_CLIENT_SUSPENDED)).isEqualTo(1);
  }

  /**
   * Builds an acting member's exchange authentication.
   *
   * @param externalClient the relayed client, or {@code null}
   * @param scopes the relayed scopes
   * @return the authentication
   */
  private static @NotNull Acting acting(
      @Nullable String externalClient, String @NotNull ... scopes) {
    List<SimpleGrantedAuthority> authorities =
        new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_EXCHANGE_MEMBER")));
    for (String scope : scopes) {
      authorities.add(new SimpleGrantedAuthority("XCH_CAPABILITY:" + scope));
    }
    return new Acting(externalClient, authorities);
  }

  /**
   * Reads one refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason)
        .count();
  }

  /** A token-less exchange authentication, like the acting member's. */
  private static final class Acting extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final @Nullable String externalClient;

    /**
     * Creates it.
     *
     * @param externalClient the relayed client, or {@code null}
     * @param authorities the authorities
     */
    Acting(@Nullable String externalClient, @NotNull List<SimpleGrantedAuthority> authorities) {
      super(authorities);
      this.externalClient = externalClient;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return subject();
    }

    @Override
    public @NotNull String subject() {
      return "5f1d2c3b-0000-0000-0000-0000000000a1";
    }

    @Override
    public @Nullable String externalClient() {
      return externalClient;
    }
  }
}
