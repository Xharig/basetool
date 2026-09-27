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

package de.greluc.krt.profit.basetool.ingest.exchange;

import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Builds the DPoP proof verifier: Spring's checks ({@code htm}, {@code htu}, {@code iat}, key
 * binding, {@code ath}, {@code jti} replay) everywhere, plus the server nonce on exchange routes
 * (REQ-XCH-006). The legacy {@code /v1} routes keep their behaviour.
 */
public final class ExchangeDpopProofValidation {

  /** The OAuth error code that asks the client to retry with the nonce of the challenge. */
  public static final String USE_DPOP_NONCE = "use_dpop_nonce";

  /** The proof claim carrying the server nonce. */
  static final String NONCE_CLAIM = "nonce";

  /** The path prefix of the exchange routes. */
  private static final String EXCHANGE_PREFIX = "/exchange/";

  /** Not instantiable. */
  private ExchangeDpopProofValidation() {}

  /**
   * Creates the proof verifier factory.
   *
   * @param nonces the server nonces
   * @return the factory
   */
  public static @NotNull DPoPProofJwtDecoderFactory factory(@NotNull ExchangeDpopNonces nonces) {
    Function<DPoPProofContext, OAuth2TokenValidator<Jwt>> defaults =
        DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(List.of());
    OAuth2TokenValidator<Jwt> nonce = nonceValidator(nonces);
    DPoPProofJwtDecoderFactory factory = new DPoPProofJwtDecoderFactory();
    factory.setJwtValidatorFactory(
        context ->
            isExchange(context)
                ? new DelegatingOAuth2TokenValidator<>(nonce, defaults.apply(context))
                : defaults.apply(context));
    return factory;
  }

  /**
   * Creates the validator that requires a current server nonce.
   *
   * @param nonces the server nonces
   * @return the validator
   */
  static @NotNull OAuth2TokenValidator<Jwt> nonceValidator(@NotNull ExchangeDpopNonces nonces) {
    OAuth2Error error =
        new OAuth2Error(
            USE_DPOP_NONCE, "The DPoP proof must carry the server nonce from DPoP-Nonce.", null);
    return proof ->
        nonces.isValid(proof.getClaimAsString(NONCE_CLAIM))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(error);
  }

  /**
   * Whether a proof targets an exchange route.
   *
   * @param context the proof's context
   * @return {@code true} when the target path lies under {@code /exchange/}
   */
  static boolean isExchange(@NotNull DPoPProofContext context) {
    try {
      String path = URI.create(context.getTargetUri()).getPath();
      return path != null && path.startsWith(EXCHANGE_PREFIX);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
