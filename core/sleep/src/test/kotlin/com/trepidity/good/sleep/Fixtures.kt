package com.trepidity.good.sleep

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

/** Local wall-clock time in Chicago, e.g. `at("2026-10-01T22:00")`. */
internal fun at(local: String): Instant = ZonedDateTime.of(LocalDateTime.parse(local), CHICAGO).toInstant()
