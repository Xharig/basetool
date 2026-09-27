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

import de.greluc.krt.profit.basetool.backend.exception.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.model.ExchangeChange;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeBlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeBlueprintPageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeProductRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeRemovedByDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintKeyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's blueprints as a snapshot and a change feed for exchange clients (REQ-XCH-013,
 * REQ-XCH-015, ADR-0224).
 *
 * <p>A snapshot pages through the member's blueprints by id and ends with the feed position it was
 * taken at, so every change made while it was read follows in the feed. The feed answers each key
 * changed after the cursor once, with its current state or a tombstone.
 */
@Service
@RequiredArgsConstructor
public class ExchangeBlueprintFeedService {

  /** The largest page a client may ask for, the published schema's limit. */
  public static final int MAX_LIMIT = 1000;

  /** The page size when the client names none. */
  public static final int DEFAULT_LIMIT = 500;

  /** Keys up to this length are used as they are; longer ones are hashed. */
  private static final int MAX_PLAIN_KEY = 128;

  /** The longest display name the published item reference carries. */
  private static final int MAX_NAME = 200;

  private final PersonalBlueprintRepository blueprintRepository;
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeChangeRetentionService retentionService;
  private final DefaultBlueprintKeyService defaultKeys;

  /**
   * Returns one page of the member's blueprints.
   *
   * @param member the member
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value #MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeBlueprintPageDto page(
      @NotNull UUID member, @Nullable String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_LIMIT);
    if (cursor == null) {
      return snapshot(member, changeRepository.maxSeq(), null, size);
    }
    ExchangeFeedCursor position = ExchangeFeedCursor.parse(cursor);
    if (position.seq() < retentionService.horizon()) {
      throw ExchangeProblemException.cursorExpired();
    }
    return position.isSnapshot()
        ? snapshot(member, position.seq(), position.afterId(), size)
        : feed(member, position.seq(), size);
  }

  /**
   * Returns the opaque key of a product.
   *
   * @param productKey the normalised product key
   * @return the product key itself, or {@code h:} and its SHA-256 when it is too long
   */
  public static @NotNull String keyOf(@NotNull String productKey) {
    if (productKey.length() <= MAX_PLAIN_KEY) {
      return productKey;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(productKey.getBytes(StandardCharsets.UTF_8));
      return "h:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /**
   * Reads one snapshot page.
   *
   * @param member the member
   * @param seq the feed position the snapshot was taken at
   * @param afterId the last row delivered, or {@code null} for the first page
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeBlueprintPageDto snapshot(
      @NotNull UUID member, long seq, @Nullable UUID afterId, int size) {
    PageRequest page = PageRequest.of(0, size + 1);
    List<PersonalBlueprint> rows =
        afterId == null
            ? blueprintRepository.findAllByOwnerUserIdOrderByIdAsc(member, page)
            : blueprintRepository.findAllByOwnerUserIdAndIdGreaterThanOrderByIdAsc(
                member, afterId, page);
    boolean more = rows.size() > size;
    List<PersonalBlueprint> delivered = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? ExchangeFeedCursor.snapshot(seq, delivered.getLast().getId()).format()
            : ExchangeFeedCursor.feed(seq).format();
    return new ExchangeBlueprintPageDto(
        delivered.stream().map(this::toDto).toList(), List.of(), next, more);
  }

  /**
   * Reads one feed page: each key changed after the position, once, with its current state or a
   * tombstone.
   *
   * @param member the member
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeBlueprintPageDto feed(@NotNull UUID member, long after, int size) {
    List<ExchangeChangeRepository.ChangedKey> changed =
        changeRepository.findChangedKeys(
            member, ExchangeResource.BLUEPRINT, after, PageRequest.of(0, size + 1));
    boolean more = changed.size() > size;
    List<ExchangeChangeRepository.ChangedKey> delivered = more ? changed.subList(0, size) : changed;
    if (delivered.isEmpty()) {
      return new ExchangeBlueprintPageDto(
          List.of(), List.of(), ExchangeFeedCursor.feed(after).format(), false);
    }
    Map<String, PersonalBlueprint> current =
        blueprintRepository
            .findAllByOwnerUserIdAndProductKeyIn(
                member,
                delivered.stream().map(ExchangeChangeRepository.ChangedKey::getEntityKey).toList())
            .stream()
            .collect(Collectors.toMap(PersonalBlueprint::getProductKey, Function.identity()));
    Map<Long, ExchangeChange> latest =
        changeRepository
            .findAllById(
                delivered.stream().map(ExchangeChangeRepository.ChangedKey::getLastSeq).toList())
            .stream()
            .collect(Collectors.toMap(ExchangeChange::getSeq, Function.identity()));
    Map<String, String> installations = installationIds(member, latest.values());
    List<ExchangeBlueprintDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (ExchangeChangeRepository.ChangedKey key : delivered) {
      PersonalBlueprint row = current.get(key.getEntityKey());
      if (row != null) {
        items.add(toDto(row));
      } else {
        ExchangeChange change = latest.get(key.getLastSeq());
        removed.add(
            new ExchangeTombstoneDto(
                keyOf(key.getEntityKey()),
                change.getChangedAt(),
                new ExchangeRemovedByDto(
                    change.getSourceChannel(),
                    change.getSourceClient(),
                    change.getSourceKey() == null
                        ? null
                        : installations.get(
                            change.getSourceClient() + "|" + change.getSourceKey()))));
      }
    }
    return new ExchangeBlueprintPageDto(
        items, removed, ExchangeFeedCursor.feed(delivered.getLast().getLastSeq()).format(), more);
  }

  /**
   * Resolves the installation ids of the clients that made the given changes.
   *
   * @param member the member
   * @param changes the changes
   * @return the opaque installation id by {@code client|key}
   */
  private @NotNull Map<String, String> installationIds(
      @NotNull UUID member, @NotNull Collection<ExchangeChange> changes) {
    List<String> keys =
        changes.stream()
            .map(ExchangeChange::getSourceKey)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (keys.isEmpty()) {
      return Map.of();
    }
    return installationRepository.findAllByUserAndKeys(member, keys).stream()
        .collect(
            Collectors.toMap(
                (ExchangeInstallation i) ->
                    i.getClient().getClientId() + "|" + i.getKeyThumbprint(),
                i -> i.getId().toString(),
                (a, b) -> a));
  }

  /**
   * Maps a blueprint; its {@code bt} is its key and the display name is cut to the published limit.
   *
   * @param row the blueprint
   * @return the feed entry
   */
  private @NotNull ExchangeBlueprintDto toDto(@NotNull PersonalBlueprint row) {
    String key = keyOf(row.getProductKey());
    String name = row.getProductName();
    return new ExchangeBlueprintDto(
        key,
        new ExchangeProductRefDto(
            key, name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name),
        row.getAcquiredAt(),
        defaultKeys.isDefault(row.getProductKey()),
        row.getNote());
  }
}
