package fr.acinq.eclair

import fr.acinq.bitcoin.{BtcAmount, ByteVector32, PublicKey, Satoshi, ScriptElt, Transaction, TxIn, TxOut}

import scala.jdk.CollectionConverters._

object KotlinUtils {
  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericSatoshi extends Numeric[Satoshi] {
    // @formatter:off
    override def plus(x: Satoshi, y: Satoshi): Satoshi = x plus y
    override def minus(x: Satoshi, y: Satoshi): Satoshi = x minus y
    override def times(x: Satoshi, y: Satoshi): Satoshi = new Satoshi(x.toLong * y.toLong)
    override def negate(x: Satoshi): Satoshi = x.unaryMinus()
    override def fromInt(x: Int): Satoshi = new Satoshi(x)
    override def toInt(x: Satoshi): Int = x.toLong.toInt
    override def toLong(x: Satoshi): Long = x.toLong
    override def toFloat(x: Satoshi): Float = x.toLong
    override def toDouble(x: Satoshi): Double = x.toLong
    override def compare(x: Satoshi, y: Satoshi): Int = x.compareTo(y)
    override def parseString(str: String): Option[Satoshi] = ???
    // @formatter:on
  }

  implicit class OrderedSatoshi(value: Satoshi) extends Ordered[Satoshi] {
    override def compare(that: Satoshi): Int = value.compareTo(that)
  }

  implicit class SatoshiToMilliSatoshiConversion(amount: Satoshi) {
    // @formatter:off
    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
    // @formatter:on
  }

  implicit class IntToSatoshi(input: Int) {
    def sat: Satoshi = new Satoshi(input)
  }

  implicit class LongToSatoshi(input: Long) {
    def sat: Satoshi = new Satoshi(input)
  }

  implicit def javatx2scala(input: java.util.List[Transaction]) : List[Transaction] = input.asScala.toList
  implicit def scalatx2java(input: Seq[Transaction]) : java.util.List[Transaction] = input.asJava
  implicit def javain2scala(input: java.util.List[TxIn]) : List[TxIn] = input.asScala.toList
  implicit def scalain2java(input: Seq[TxIn]) : java.util.List[TxIn] = input.asJava
  implicit def javaout2scala(input: java.util.List[TxOut]) : List[TxOut] = input.asScala.toList
  implicit def scalaout2java(input: Seq[TxOut]) : java.util.List[TxOut] = input.asJava
  implicit def javascriptelt2scala(input: java.util.List[ScriptElt]) : List[ScriptElt] = input.asScala.toList
  implicit def scalascriptelt2java(input: Seq[ScriptElt]) : java.util.List[ScriptElt] = input.asJava
  implicit def scalapubkey2java(input: Seq[PublicKey]) : java.util.List[PublicKey] = input.asJava
  implicit def scalabytes2java(input: Seq[fr.acinq.bitcoin.ByteVector]) : java.util.List[fr.acinq.bitcoin.ByteVector] = input.asJava
  implicit def pair2tuple[A](input: kotlin.Pair[A, java.lang.Boolean]): Tuple2[A, Boolean] = (input.getFirst, if (input.getSecond) true else false)
  implicit def satoshi2long(input: Satoshi): Long = input.toLong
  implicit def btcamount2satoshi(input: BtcAmount): Satoshi = input.toSatoshi
}
