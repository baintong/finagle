package com.twitter.finagle.mysql

import com.twitter.finagle.mysql.transport.MysqlBuf
import com.twitter.util.TwitterDateFormat
import java.sql.{Date, Timestamp}
import java.text.ParsePosition
import java.util.logging.Logger
import java.util.{Calendar, TimeZone}

/**
 * Defines a Value ADT that represents the domain of values
 * received from a mysql server.
 */
sealed trait Value
case class ByteValue(b: Byte) extends Value
case class ShortValue(s: Short) extends Value
case class IntValue(i: Int) extends Value
case class LongValue(l: Long) extends Value
case class BigIntValue(bi: BigInt) extends Value
case class FloatValue(f: Float) extends Value
case class DoubleValue(d: Double) extends Value
case class StringValue(s: String) extends Value
case object EmptyValue extends Value
case object NullValue extends Value

/**
 * A RawValue contains the raw bytes that represent
 * a value and enough meta data to decode the
 * bytes.
 *
 * @param typ The MySQL [[Type type]] code for this value.
 * @param charset The [[Charset charset]] encoding of the bytes.
 * @param isBinary Disambiguates between the text and binary protocol.
 * @param bytes The raw bytes for this value.
 */
case class RawValue(
  typ: Short,
  charset: Short,
  isBinary: Boolean,
  bytes: Array[Byte]
) extends Value

/**
 * A type class used for injecting values of a domain type `A` into
 * [[com.twitter.finagle.mysql.Value Values]] for insertion into a MySQL
 * database.
 */
private[mysql] trait Injectable[A] {
  def apply(a: A): Value
}

/**
 * A type class used for extracting [[com.twitter.finagle.mysql.Value Values]]
 * into a domain type `A`.
 */
private[mysql] trait Extractable[A] {
  def unapply(v: Value): Option[A]
}

/**
 * An injector/extractor of [[java.sql.Timestamp]] values.
 *
 * @param injectionTimeZone The timezone in which
 * [[java.sql.Timestamp Timestamps]] are injected into MySQL TIMESTAMP values.
 * @param extractionTimeZone The timezone in which TIMESTAMP and DATETIME
 * rows are extracted from database rows into [[java.sql.Timestamp Timestamps]].
 */
class TimestampValue(val injectionTimeZone: TimeZone, val extractionTimeZone: TimeZone)
    extends Injectable[Timestamp]
    with Extractable[Timestamp] {

  /**
   * Injects a [[java.sql.Timestamp]] into a
   * [[com.twitter.finagle.mysql.RawValue]] in a given `injectionTimeZone`
   */
  def apply(ts: Timestamp): Value = {
    val bytes = new Array[Byte](11)
    val bw = MysqlBuf.writer(bytes)
    val cal = Calendar.getInstance(injectionTimeZone)
    cal.setTimeInMillis(ts.getTime)
    bw.writeShortLE(cal.get(Calendar.YEAR))
    bw.writeByte(cal.get(Calendar.MONTH) + 1) // increment 0 indexed month
    bw.writeByte(cal.get(Calendar.DATE))
    bw.writeByte(cal.get(Calendar.HOUR_OF_DAY))
    bw.writeByte(cal.get(Calendar.MINUTE))
    bw.writeByte(cal.get(Calendar.SECOND))
    bw.writeIntLE(ts.getNanos / 1000) // sub-second part is written as microseconds
    RawValue(Type.Timestamp, Charset.Binary, isBinary = true, bytes)
  }

  /**
   * Value extractor for [[java.sql.Timestamp]].
   *
   * Extracts timestamps in `extractionTimeZone` for values encoded in either
   * the binary or text MySQL protocols.
   */
  def unapply(v: Value): Option[Timestamp] =
    TimestampValue.fromValue(v, extractionTimeZone)

}

/**
 * Extracts a value in UTC. To use a different time zone, create an instance of
 * [[com.twitter.finagle.mysql.TimestampValue]].
 */
object TimestampValue
    extends TimestampValue(
      TimeZone.getDefault(),
      TimeZone.getTimeZone("UTC")
    ) {
  private[this] val log = Logger.getLogger("finagle-mysql")

  @deprecated(
    "Injects `java.sql.Timestamp`s in local time and extracts them in UTC." +
      "To use a different time zone, create an instance of " +
      "TimestampValue(InjectionTimeZone, ExtractionTimeZone)",
    "6.20.2"
  )
  override def apply(ts: Timestamp): Value = {
    log.warning(
      "Injecting timezone-less `java.sql.Timestamp` with a hardcoded local timezone (%s)"
        .format(injectionTimeZone.getID)
    )
    super.apply(ts)
  }

  @deprecated(
    "Injects `java.sql.Timestamp`s in local time and extracts them in UTC." +
      "To use a different time zone, create an instance of " +
      "TimestampValue(InjectionTimeZone, ExtractionTimeZone)",
    "6.20.2"
  )
  override def unapply(v: Value): Option[Timestamp] = {
    log.warning(
      "Extracting TIMESTAMP or DATETIME row as a `java.sql.Timestamp` with a hardcoded timezone (%s)"
        .format(extractionTimeZone.getID)
    )
    super.unapply(v)
  }

  private[mysql] def isTimestamp(value: Value): Boolean =
    value match {
      case raw: RawValue if raw.typ == Type.Timestamp || raw.typ == Type.DateTime => true
      case _ => false
    }

  /**
   * Extracts timestamps in the given `TimeZone` for [[Value]]s encoded in either
   * the binary or text MySQL protocols.
   */
  private[mysql] def fromValue(value: Value, timeZone: TimeZone): Option[Timestamp] =
    value match {
      case raw: RawValue if isTimestamp(raw) =>
        val timestamp = if (!raw.isBinary) {
          val str = new String(raw.bytes, Charset(raw.charset))
          fromString(str, timeZone)
        } else {
          fromBytes(raw.bytes, timeZone)
        }
        Some(timestamp)
      case _ => None
    }

  /**
   * Timestamp object that can appropriately
   * represent MySQL zero Timestamp.
   */
  private[this] object Zero extends Timestamp(0) {
    override val getTime: Long = 0L
    override val toString: String = "0000-00-00 00:00:00"
  }

  /**
   * Extracts the fractional part of a timestamp, up to the
   * nanoseconds. It takes care of padding properly so that .1
   * is interpreted as 100 millis and not 1 nanoseconds (like
   * SimpleDateFormat wrongly does.)
   */
  private[this] object Nanos {
    def unapply(str: String): Option[Int] = {
      str match {
        case "" => Some(0)
        case s: String if !s.startsWith(".") => None
        case s: String if s.length() > 10 => None
        case s: String => Some(s.stripPrefix(".").padTo(9, '0').toInt)
        case _ => None
      }
    }
  }

  /**
   * Convert a string-encoded timestamp into a [[java.sql.Timestamp]] in a given
   * timezone.
   *
   * Invalid DATETIME or TIMESTAMP values are converted to the “zero” value
   * ('0000-00-00 00:00:00').
   *
   * @param str A string representing a TIMESTAMP written in the
   * MySQL text protocol.
   */
  private[this] def fromString(str: String, timeZone: TimeZone): Timestamp = {
    if (str == Zero.toString) {
      return Zero
    }

    val parsePosition = new ParsePosition(0)
    val format = TwitterDateFormat("yyyy-MM-dd HH:mm:ss")
    format.setTimeZone(timeZone)
    val timeInMillis = format.parse(str, parsePosition).getTime

    // Parse fractional part
    str.substring(parsePosition.getIndex) match {
      case Nanos(nanos) =>
        val ts = new Timestamp(timeInMillis)
        ts.setNanos(nanos)
        ts
      case _ => Zero
    }
  }

  /**
   * Convert a binary-encoded timestamp into a [[java.sql.Timestamp]] in a given
   * timezone.
   *
   * Invalid DATETIME or TIMESTAMP values are converted to the “zero” value
   * ('0000-00-00 00:00:00').
   *
   * @param bytes A byte-array representing a TIMESTAMP written in the
   * MySQL binary protocol.
   */
  private[this] def fromBytes(bytes: Array[Byte], timeZone: TimeZone): Timestamp = {
    if (bytes.isEmpty) {
      return Zero
    }

    var year, month, day, hour, min, sec, micro = 0
    val br = MysqlBuf.reader(bytes)

    try {
      // If the len was not zero, we can strictly
      // expect year, month, and day to be included.
      if (br.remaining >= 4) {
        year = br.readUnsignedShortLE()
        month = br.readUnsignedByte()
        day = br.readUnsignedByte()
      } else {
        return Zero
      }

      // if the time-part is 00:00:00, it isn't included.
      if (br.remaining >= 3) {
        hour = br.readUnsignedByte()
        min = br.readUnsignedByte()
        sec = br.readUnsignedByte()
      }

      // if the sub-seconds are 0, they aren't included.
      if (br.remaining >= 4) {
        micro = br.readIntLE()
      }

      val cal = Calendar.getInstance(timeZone)
      cal.set(year, month - 1, day, hour, min, sec)

      val ts = new Timestamp(0)
      ts.setTime(cal.getTimeInMillis)
      ts.setNanos(micro * 1000)
      ts
    } finally br.close()
  }

}

object DateValue extends Injectable[Date] with Extractable[Date] {

  /**
   * Creates a RawValue from a java.sql.Date
   */
  def apply(date: Date): Value = {
    val bytes = new Array[Byte](4)
    val bw = MysqlBuf.writer(bytes)
    val cal = Calendar.getInstance
    cal.setTimeInMillis(date.getTime)
    bw.writeShortLE(cal.get(Calendar.YEAR))
    bw.writeByte(cal.get(Calendar.MONTH) + 1) // increment 0 indexed month
    bw.writeByte(cal.get(Calendar.DATE))
    RawValue(Type.Date, Charset.Binary, true, bytes)
  }

  /**
   * Value extractor for java.sql.Date
   */
  def unapply(v: Value): Option[Date] = v match {
    case RawValue(Type.Date, Charset.Binary, false, bytes) =>
      val str = new String(bytes, Charset(Charset.Binary))
      if (str == Zero.toString) Some(Zero)
      else Some(Date.valueOf(str))

    case RawValue(Type.Date, Charset.Binary, true, bytes) =>
      Some(fromBytes(bytes))
    case _ => None
  }

  /**
   * Date object that can appropriately
   * represent MySQL zero Date.
   */
  private[this] object Zero extends Date(0) {
    override val getTime = 0L
    override val toString = "0000-00-00"
  }

  /**
   * Creates a DateValue from its MySQL binary representation.
   * Invalid DATE values are converted to
   * the “zero” value of the appropriate type
   * ('0000-00-00' or '0000-00-00 00:00:00').
   * @param bytes An array of bytes representing a DATE written in the
   * MySQL binary protocol.
   */
  private[this] def fromBytes(bytes: Array[Byte]): Date = {
    if (bytes.isEmpty) {
      return Zero
    }

    var year, month, day = 0
    val br = MysqlBuf.reader(bytes)
    try {
      if (br.remaining >= 4) {
        year = br.readUnsignedShortLE()
        month = br.readUnsignedByte()
        day = br.readUnsignedByte()
      } else {
        return Zero
      }

      val cal = Calendar.getInstance
      cal.set(year, month - 1, day)

      new Date(cal.getTimeInMillis)
    } finally br.close()
  }
}

object BigDecimalValue extends Injectable[BigDecimal] with Extractable[BigDecimal] {
  def apply(b: BigDecimal): Value = {
    val str = b.toString.getBytes(Charset(Charset.Binary))
    RawValue(Type.NewDecimal, Charset.Binary, true, str)
  }

  def unapply(v: Value): Option[BigDecimal] = v match {
    case RawValue(Type.NewDecimal, Charset.Binary, _, bytes) =>
      Some(BigDecimal(new String(bytes, Charset(Charset.Binary))))
    case _ => None
  }
}
