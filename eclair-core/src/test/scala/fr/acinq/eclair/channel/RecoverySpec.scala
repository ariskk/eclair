package fr.acinq.eclair.channel

import akka.testkit.TestProbe
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.crypto.{Generators, KeyManager}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.{ClaimP2WPKHOutputTx, InputInfo}
import fr.acinq.eclair.wire.{ChannelReestablish, CommitSig, Error, Init, RevokeAndAck}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._
import fr.acinq.eclair.KotlinUtils._


class RecoverySpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = test.tags.contains("disable-offline-mismatch") match {
      case false => init()
      case true => init(nodeParamsA = Alice.nodeParams.copy(onChainFeeConf = Alice.nodeParams.onChainFeeConf.copy(closeOnOfflineMismatch = false)))
    }
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  def aliceInit = Init(TestConstants.Alice.nodeParams.features)

  def bobInit = Init(TestConstants.Bob.nodeParams.features)

  test("use funding pubkeys from publish commitment to spend our output") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    val oldStateData = alice.stateData
    // then we add an htlc and sign it
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg(ChannelCommandResponse.Ok)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice will receive neither the revocation nor the commit sig
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    val ce = bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(alice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual tests starts here: let's see what we can do with Bob's commit tx
    sender.send(alice, WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx))

    // from Bob's commit tx we can extract both funding public keys
    val (pub1, pub2) = {
      val script = Script.parse(bobCommitTx.txIn.get(0).witness.last).toList
      script match {
        case OP_2.INSTANCE :: op1 :: op2 :: OP_2.INSTANCE :: OP_CHECKMULTISIG.INSTANCE :: Nil if op1.isPush(33) && op2.isPush(33) => (op1.asInstanceOf[OP_PUSHDATA].data, op2.asInstanceOf[OP_PUSHDATA].data)
      }
    }
    //val OP_2.INSTANCE :: new OP_PUSHDATA(pub1) :: new OP_PUSHDATA(pub2) :: OP_2.INSTANCE :: OP_CHECKMULTISIG.INSTANCE :: Nil = Script.parse(bobCommitTx.txIn(0).witness.stack.last)
    // from Bob's commit tx we can also extract our p2wpkh output
    val ourOutput = bobCommitTx.txOut.find(_.publicKeyScript.size() == 22).get

    val pubKeyHash = Script.parse(ourOutput.publicKeyScript).toList match {
      case OP_0.INSTANCE :: op :: Nil if op.isPush(20) => op.asInstanceOf[OP_PUSHDATA].data
    }

    val keyManager = TestConstants.Alice.nodeParams.keyManager

    // find our funding pub key
    val fundingPubKey = Seq(new PublicKey(pub1), new PublicKey(pub2)).find {
      pub =>
        val channelKeyPath = KeyManager.channelKeyPath(pub)
        val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)
        pubKeyHash.contentEquals(localPubkey.hash160)
    } get

    // compute our to-remote pubkey
    val channelKeyPath = KeyManager.channelKeyPath(fundingPubKey)
    val ourToRemotePubKey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, ce.myCurrentPerCommitmentPoint)

    // spend our output
    val tx = new Transaction(2,
      new TxIn(new OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), TxIn.SEQUENCE_FINAL) :: Nil,
      new TxOut(1000 sat, Script.pay2pkh(fr.acinq.eclair.randomKey.publicKey)) :: Nil,
      0)

    val sig = keyManager.sign(
      ClaimP2WPKHOutputTx(InputInfo(new OutPoint(bobCommitTx, bobCommitTx.txOut.indexOf(ourOutput)), ourOutput, Script.pay2pkh(ourToRemotePubKey)), tx),
      keyManager.paymentPoint(channelKeyPath),
      ce.myCurrentPerCommitmentPoint)
    val tx1 = tx.updateWitness(0, new ScriptWitness(Scripts.der(sig) :: ourToRemotePubKey.value :: Nil))
    Transaction.correctlySpends(tx1, bobCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
}
