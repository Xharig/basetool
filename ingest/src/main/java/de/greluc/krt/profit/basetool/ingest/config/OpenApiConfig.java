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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI document SpringDoc generates for the ingest gateway; it lists no operation,
 * because the gateway's only API is the exchange, whose contract is a committed document of its own
 * (ADR-0216).
 */
@Configuration
public class OpenApiConfig {

  /**
   * Returns the {@link OpenAPI} root document SpringDoc writes to {@code openapi.json}.
   *
   * @return the {@link OpenAPI} root document for the ingest gateway
   */
  @Bean
  public OpenAPI ingestOpenApi() {
    return new OpenAPI()
        .openapi("3.1.1")
        .info(
            new Info()
                .title("KRT Basetool Ingest Gateway API")
                .version("1.0")
                .description(
                    "The ingest gateway of the KRT Basetool publishes no operation in this"
                        + " document. Its only API is the exchange for approved client software;"
                        + " its contract is served at /exchange/v1/openapi.json and documented at"
                        + " https://krt-profit.github.io/basetool/."));
  }
}
