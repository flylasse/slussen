package slussen.app.ui

import slussen.core.port.BankAccountType
import slussen.pluginapi.value.Amount
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the screens show amounts, times and kinds. Every time is shown in the device's local time (UC-008 BR-008,
 * NFR-018): the [zone] is the device's, taken once by the composition root.
 */
class Formats(val zone: ZoneId) {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val money = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ROOT))

    /** An amount in thousandths of the currency unit, with two decimals, a minus sign for an outflow. */
    fun amount(amount: Amount): String =
        money.format(BigDecimal.valueOf(amount.thousandths, 3).setScale(2, RoundingMode.HALF_EVEN)).replace("-", "−")

    /** A difference, with a plus sign when positive. */
    fun difference(amount: Amount): String = (if (amount.thousandths > 0) "+" else "") + amount(amount)

    fun time(instant: Instant): String = time.format(instant.atZone(zone))

    fun dateTime(instant: Instant): String = dateTime.format(instant.atZone(zone))

    fun date(instant: Instant): String = date.format(instant.atZone(zone))

    fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

    fun type(type: BankAccountType): String = type.text
}
