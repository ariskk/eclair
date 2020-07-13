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

package fr.acinq.eclair.crypto

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, DeterministicWallet, KeyPath, PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.{derivePrivateKey, _}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eclair.{Features, ShortChannelId, secureRandom}
import scodec.bits.ByteVector

object LocalKeyManager {
  def channelKeyBasePath(chainHash: ByteVector32) = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => new KeyPath("46'/1'")// DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(1) :: Nil
    case Block.LivenetGenesisBlock.hash => new KeyPath("47'/1'") // DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(1) :: Nil
  }


  // WARNING: if you change this path, you will change your node id even if the seed remains the same!!!
  // Note that the node path and the above channel path are on different branches so even if the
  // node key is compromised there is no way to retrieve the wallet keys
  def nodeKeyBasePath(chainHash: ByteVector32) = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => new KeyPath("46'/0'") // DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => new KeyPath("47'/0")// DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(0) :: Nil
  }
}

/**
  * This class manages secrets and private keys.
  * It exports points and public keys, and provides signing methods
  *
  * @param seed seed from which keys will be derived
  */
class LocalKeyManager(seed: ByteVector, chainHash: ByteVector32) extends KeyManager {

  def this(seed: Array[Byte], chainHash: ByteVector32) = this(ByteVector.view(seed), chainHash)

  def this(seed: ByteVector32, chainHash: ByteVector32) = this(ByteVector.view(seed.toByteArray), chainHash)

  private val master = DeterministicWallet.generate(seed.toArray)

  override val nodeKey = DeterministicWallet.derivePrivateKey(master, LocalKeyManager.nodeKeyBasePath(chainHash))
  override val nodeId = nodeKey.publicKey

  private val privateKeys: LoadingCache[KeyPath, ExtendedPrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPrivateKey](new CacheLoader[KeyPath, ExtendedPrivateKey] {
    override def load(keyPath: KeyPath): ExtendedPrivateKey = derivePrivateKey(master, keyPath)
  })

  private val publicKeys: LoadingCache[KeyPath, ExtendedPublicKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPublicKey](new CacheLoader[KeyPath, ExtendedPublicKey] {
    override def load(keyPath: KeyPath): ExtendedPublicKey = publicKey(privateKeys.get(keyPath))
  })

  private def internalKeyPath(channelKeyPath: KeyPath, index: Long): KeyPath = (LocalKeyManager.channelKeyBasePath(chainHash) append channelKeyPath) append index

  private def fundingPrivateKey(channelKeyPath: KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, hardened(0)))

  private def revocationSecret(channelKeyPath: KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, hardened(1)))

  private def paymentSecret(channelKeyPath: KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, hardened(2)))

  private def delayedPaymentSecret(channelKeyPath: KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, hardened(3)))

  private def htlcSecret(channelKeyPath: KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, hardened(4)))

  private def shaSeed(channelKeyPath: KeyPath) = Crypto.sha256(privateKeys.get(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.toByteArray :+ 1.toByte)

  override def newFundingKeyPath(isFunder: Boolean): KeyPath = {
    import scala.jdk.CollectionConverters._

    val last: java.lang.Long = DeterministicWallet.hardened(if (isFunder) 1 else 0)
    def next(): java.lang.Long = secureRandom.nextInt() & 0xFFFFFFFFL
    new KeyPath(Seq(next(), next(), next(), next(), next(), next(), next(), next(), last).asJava)
  }

  override def fundingPublicKey(channelKeyPath: KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, hardened(0)))

  override def revocationPoint(channelKeyPath: KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, hardened(1)))

  override def paymentPoint(channelKeyPath: KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, hardened(2)))

  override def delayedPaymentPoint(channelKeyPath: KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, hardened(3)))

  override def htlcPoint(channelKeyPath: KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, hardened(4)))

  override def commitmentSecret(channelKeyPath: KeyPath, index: Long) = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  override def commitmentPoint(channelKeyPath: KeyPath, index: Long) = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

  /**
    *
    * @param tx        input transaction
    * @param publicKey extended public key
    * @return a signature generated with the private key that matches the input
    *         extended public key
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey): ByteVector64 = {
    val privateKey = privateKeys.get(publicKey.path)
    Transactions.sign(tx, privateKey.privateKey)
  }

  /**
    * This method is used to spend funds send to htlc keys/delayed keys
    *
    * @param tx          input transaction
    * @param publicKey   extended public key
    * @param remotePoint remote point
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote point.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey): ByteVector64 = {
    val privateKey = privateKeys.get(publicKey.path)
    val currentKey = Generators.derivePrivKey(privateKey.privateKey, remotePoint)
    Transactions.sign(tx, currentKey)
  }

  
  /**
    * Ths method is used to spend revoked transactions, with the corresponding revocation key
    *
    * @param tx           input transaction
    * @param publicKey    extended public key
    * @param remoteSecret remote secret
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote secret.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey): ByteVector64 = {
    val privateKey = privateKeys.get(publicKey.path)
    val currentKey = Generators.revocationPrivKey(privateKey.privateKey, remoteSecret)
    Transactions.sign(tx, currentKey)
  }

  override def signChannelAnnouncement(fundingKeyPath: KeyPath, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: Features): (ByteVector64, ByteVector64) = {
    val localNodeSecret = nodeKey.privateKey
    val localFundingPrivKey = privateKeys.get(fundingKeyPath).privateKey
    Announcements.signChannelAnnouncement(chainHash, shortChannelId, localNodeSecret, remoteNodeId, localFundingPrivKey, remoteFundingKey, features)
  }
}
