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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeSchemas;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The authenticated exchange routes built so far (REQ-XCH-001). Each request has passed both
 * exchange gates; a body is checked against its v1 schema before the relay, undeclared fields are
 * reported as {@code UNKNOWN_FIELD} warnings where the answer carries warnings, and the backend's
 * answer is checked against its schema before it reaches the client. Kept out of the extractor's
 * API document; the committed exchange document describes these routes.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/exchange/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeController {

  /** The API version the service document reports. */
  static final String API_VERSION = "1.0";

  /** The largest change set, as the contract fixes it. */
  static final int BATCH_MAX_OPS = 500;

  /** The warning code of an undeclared field. */
  static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";

  private static final String BACKEND = "/api/v1/exchange";

  private final ExchangeRelay relay;
  private final ExchangeSchemas schemas;
  private final ExchangeGatewayProperties properties;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;

  /**
   * Returns the service document: what this token may do and what the server expects.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the document
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> serviceDocument(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    ObjectNode document = objectMapper.createObjectNode();
    document.put("apiVersion", API_VERSION);
    ArrayNode capabilities = document.putArray("capabilities");
    new TreeSet<>(context.capabilities()).forEach(capabilities::add);
    ExchangeRelay.Result installation =
        relay.forward(HttpMethod.GET, BACKEND + "/me/installation", null, context, acceptLanguage);
    if (installation.isOk() && installation.body().get("installationId") != null) {
      document.set("installationId", installation.body().get("installationId"));
    }
    ObjectNode limits = document.putObject("limits");
    limits.put("batchMaxOps", BATCH_MAX_OPS);
    if (context.client().requestsPerMinute() != null) {
      limits.put("requestsPerMinute", context.client().requestsPerMinute());
    }
    if (context.client().writesPerDay() != null) {
      limits.put("writesPerDay", context.client().writesPerDay());
    }
    document.putArray("deprecations");
    document.put("docsUrl", properties.docsUrl());
    document.put("minClientVersion", context.client().minClientVersion());
    return answer(document, "service-document.schema.json");
  }

  /**
   * Labels the calling installation.
   *
   * @param body the label
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the labelled installation
   */
  @PostMapping(value = "/me/installation", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> installation(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "installation.schema.json",
        "/me/installation",
        "installation.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Answers whether an RSI handle belongs to the member, never disclosing the stored one.
   *
   * @param body the handle
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return {@code match}, {@code mismatch} or {@code unknown}
   */
  @PostMapping(value = "/me/account-check", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> accountCheck(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "account-check-request.schema.json",
        "/me/account-check",
        "account-check-response.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Resolves item references.
   *
   * @param body the references
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return one result per reference
   */
  @PostMapping(value = "/catalog/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> resolve(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "resolve-request.schema.json",
        "/catalog/resolve",
        "resolve-response.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Lists the Lager's non-hidden locations.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the locations
   */
  @GetMapping("/catalog/locations")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> locations(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    return relayed(
        relay.forward(
            HttpMethod.GET, BACKEND + "/catalog/locations", null, context, acceptLanguage),
        "location-list.schema.json",
        List.of());
  }

  /**
   * Checks a body, relays it and checks the answer.
   *
   * @param body the request body
   * @param requestSchema the body's schema
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the answer's schema
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer or a problem
   */
  private @NotNull ResponseEntity<?> relayBody(
      @NotNull JsonNode body,
      @NotNull String requestSchema,
      @NotNull String path,
      @NotNull String responseSchema,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    List<ExchangeSchemas.Violation> violations = schemas.validate(requestSchema, body);
    if (!violations.isEmpty()) {
      return schemaInvalid(violations);
    }
    List<String> unknown = schemas.unknownFields(requestSchema, body);
    return relayed(
        relay.forward(HttpMethod.POST, BACKEND + path, body, context, acceptLanguage),
        responseSchema,
        unknown);
  }

  /**
   * Turns a relay result into the answer, checking a usable one against its schema and adding the
   * unknown-field warnings where the schema carries warnings.
   *
   * @param result the relay result
   * @param responseSchema the answer's schema
   * @param unknown the request's undeclared fields
   * @return the answer or a problem
   */
  private @NotNull ResponseEntity<?> relayed(
      @NotNull ExchangeRelay.Result result,
      @NotNull String responseSchema,
      @NotNull List<String> unknown) {
    if (!result.isOk()) {
      return problem(result.status(), result.code(), result.detail());
    }
    JsonNode body = result.body();
    if (!unknown.isEmpty()
        && body instanceof ObjectNode object
        && acceptsWarnings(responseSchema)) {
      ArrayNode warnings =
          object.get("warnings") instanceof ArrayNode existing
              ? existing
              : object.putArray("warnings");
      for (String pointer : unknown) {
        if (warnings.size() >= ExchangeSchemas.MAX_REPORTED) {
          break;
        }
        ObjectNode warning = warnings.addObject();
        warning.put("pointer", pointer);
        warning.put("code", UNKNOWN_FIELD);
      }
    }
    return answer(body, responseSchema);
  }

  /**
   * Answers with a document once it matches its schema.
   *
   * @param body the document
   * @param schema its schema
   * @return {@code 200}, or {@code 502} when the document breaks the contract
   */
  private @NotNull ResponseEntity<?> answer(@NotNull JsonNode body, @NotNull String schema) {
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      log.warn(
          "Exchange answer breaks {}: first violation at {}",
          schema,
          violations.getFirst().pointer());
      return failed();
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }

  /**
   * Whether a response schema carries a {@code warnings} list.
   *
   * @param responseSchema the schema's file name
   * @return {@code true} for the resolve and change answers
   */
  private static boolean acceptsWarnings(@NotNull String responseSchema) {
    return "resolve-response.schema.json".equals(responseSchema)
        || "change-result.schema.json".equals(responseSchema);
  }

  /**
   * Answers a request body that breaks its schema.
   *
   * @param violations the violations
   * @return {@code 400 SCHEMA_INVALID} with {@code errors[]}
   */
  private @NotNull ResponseEntity<?> schemaInvalid(
      @NotNull List<ExchangeSchemas.Violation> violations) {
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.BAD_REQUEST,
            "Bad request",
            "SCHEMA_INVALID",
            "The body does not match the v1 schema.");
    problem.setProperty(
        "errors",
        violations.stream()
            .map(v -> Map.of("pointer", v.pointer(), "message", v.message()))
            .toList());
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Answers a relay failure.
   *
   * @return {@code 502 BACKEND_RELAY_FAILED}
   */
  private @NotNull ResponseEntity<?> failed() {
    ExchangeRelay.Result failed = ExchangeRelay.Result.failed();
    return problem(failed.status(), failed.code(), failed.detail());
  }

  /**
   * Builds a problem answer.
   *
   * @param status the status
   * @param code the code
   * @param detail the detail
   * @return the answer
   */
  private @NotNull ResponseEntity<?> problem(
      int status, @Nullable String code, @Nullable String detail) {
    HttpStatus httpStatus = HttpStatus.valueOf(status);
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            httpStatus,
            httpStatus.getReasonPhrase(),
            code == null ? ExchangeRelay.RELAY_FAILED : code,
            detail);
    return ResponseEntity.status(httpStatus)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
