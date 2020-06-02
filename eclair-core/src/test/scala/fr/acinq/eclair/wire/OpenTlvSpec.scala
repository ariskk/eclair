/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.wire.OpenChannelTlv.ChannelVersionTlv
import org.scalatest.funsuite.AnyFunSuite
import scodec.{Attempt, DecodeResult}
import scodec.bits._

class OpenTlvSpec extends AnyFunSuite {

  test("channel version tlv") {

    val refs = Map(
      ChannelVersion.STANDARD -> hex"fe47000000 00000001".bits,
      (ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE) -> hex"fe47000000 00000009".bits
    )

    refs.foreach { case (cv, bits) => assert(OpenChannelTlv.openTlvCodec.encode(TlvStream(ChannelVersionTlv(cv))) === Attempt.Successful(bits)) }
    refs.foreach { case (cv, bits) => assert(OpenChannelTlv.openTlvCodec.decode(bits) === Attempt.successful(DecodeResult(TlvStream(ChannelVersionTlv(cv)), BitVector.empty))) }
  }
}