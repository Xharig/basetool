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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the gateway reads the exchange registry the backend mirrors into Redis (REQ-XCH-003).
 *
 * @param registryKey the Redis key of the mirror document; must stay under {@code exchange:}, the
 *     only family the gateway's ACL user may read there
 * @param registryCacheTtl how long a read registry is reused before Redis is asked again
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange")
public record ExchangeGatewayProperties(
    @NotBlank @Pattern(regexp = "exchange:[a-z0-9:_-]+") @DefaultValue("exchange:registry")
        String registryKey,
    @NotNull @DefaultValue("PT5S") Duration registryCacheTtl) {}
