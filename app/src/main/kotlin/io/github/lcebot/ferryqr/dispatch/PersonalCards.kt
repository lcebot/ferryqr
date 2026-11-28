package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.text.format.DateUtils
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.BoardingPassParser
import io.github.lcebot.ferryqr.content.BoardingPassPayload
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.sensitiveKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A boarding pass: each flight's route with its own details, then the passenger's, then the
 * content as the code holds it, which keeps everything the card leaves out. Blank fields are left
 * out and fields that read as nothing known are shown as printed. A pass gives its dates without
 * the year, which [scannedAt] settles.
 */
internal fun Cards.boardingPass(context: Context, payload: BoardingPassPayload, scannedAt: Long): CardBody {
    val pass = payload.pass
    val zone = ZoneId.systemDefault()
    val scanned = Instant.ofEpochMilli(scannedAt).atZone(zone).toLocalDate()
    fun rows(vararg pairs: Pair<Int, String?>) =
        pairs.mapNotNull { (label, value) -> value?.takeIf { it.isNotEmpty() }?.let { context.getString(label) to it } }
    val sections = buildList {
        pass.legs.forEachIndexed { index, leg ->
            val caption = listOfNotNull(leg.flight.ifEmpty { null }, flightDay(context, leg.dayOfYear, scanned, zone))
            add(
                CardSection.Route(
                    label = if (pass.legs.size > 1) context.getString(R.string.boarding_leg, index + 1) else null,
                    from = leg.from,
                    to = leg.to,
                    caption = caption.joinToString("  "),
                ),
            )
            val details = rows(
                R.string.boarding_seat to leg.shownSeat,
                R.string.boarding_class to leg.compartment,
                R.string.boarding_sequence to leg.shownSequence,
                R.string.boarding_booking_reference to leg.bookingReference,
            )
            if (details.isNotEmpty()) add(CardSection.Fields(details))
        }
        val traveller = rows(
            R.string.boarding_passenger to pass.passenger,
            R.string.boarding_frequent_flyer to pass.frequentFlyer,
            R.string.boarding_bag_tag to pass.bagTag,
        )
        if (traveller.isNotEmpty()) add(CardSection.Fields(traveller))
        add(CardSection.Raw(payload.raw))
    }
    val notes = listOfNotNull(
        context.getString(R.string.boarding_incomplete).takeUnless { pass.complete },
        context.getString(R.string.boarding_privacy),
    )
    return CardBody(
        headline = "",
        notes = notes,
        actions = listOf(copyAction(payload.raw, payload.sensitiveKind != null), share(payload.raw)),
        sections = sections,
    )
}

/** The flight's day as a month and day, or as printed when it is not a day of the year. */
private fun flightDay(context: Context, dayOfYear: String, scanned: LocalDate, zone: ZoneId): String? {
    val date = dayOfYear.toIntOrNull()?.let { BoardingPassParser.flightDate(it, scanned) } ?: return dayOfYear.ifEmpty { null }
    val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
}

/**
 * A contact, led by its name. One without a name, organisation, phone or email is led by the
 * first detail it has, which says more of it than the text of its code.
 */
internal fun Cards.contact(payload: ContactPayload, names: ContentNames): CardBody {
    val c = payload.contact
    val details = (c.phones + c.emails + listOfNotNull(c.organization, c.title, c.address, c.url, c.note))
    val shown = if (names.carried.name != null) names.headline else details.firstOrNull() ?: names.headline
    return CardBody(
        headline = shown,
        body = details.filter { it != shown }.joinToString("\n").ifEmpty { null },
        actions = listOf(
            CardAction(R.string.add_contact, R.drawable.ic_person_add) {
                if (Launch.addContact(it, c)) ActionResult.Handoff else ActionResult.Unavailable
            },
            copyAction(payload.raw, payload.sensitiveKind != null),
        ),
    )
}

/**
 * An event, led by its title. One without a title is led by its time, which says more of it
 * than the text of its code.
 */
internal fun Cards.event(context: Context, payload: EventPayload, names: ContentNames): CardBody {
    val e = payload.event
    val titled = names.carried.name != null
    val time = e.begin?.let { begin ->
        val flags = if (e.allDay) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
        DateUtils.formatDateRange(context, begin, e.end ?: begin, flags)
    }
    return CardBody(
        headline = if (titled) names.headline else time ?: names.headline,
        body = listOfNotNull(time.takeIf { titled }, e.location, e.description).joinToString("\n").ifEmpty { null },
        actions = listOf(
            CardAction(R.string.add_event, R.drawable.ic_event) {
                if (Launch.addEvent(it, e)) ActionResult.Handoff else ActionResult.Unavailable
            },
            copyAction(payload.raw, payload.sensitiveKind != null),
        ),
    )
}
