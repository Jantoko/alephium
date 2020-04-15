package org.alephium.protocol.model

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.PubScript
import org.alephium.serde._

final case class TxOutputPoint(shortKey: Int, txHash: Hash, outputIndex: Int) {
  def fromGroup(implicit config: GroupConfig): GroupIndex = PubScript.groupIndex(shortKey)
}

object TxOutputPoint {
  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputPoint = {
    assume(outputIndex >= 0 && outputIndex < transaction.unsigned.outputs.length)
    val output = transaction.unsigned.outputs(outputIndex)
    TxOutputPoint(output.shortKey, transaction.hash, outputIndex)
  }

  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxOutputPoint] =
    Serde.forProduct3(apply, ti => (ti.shortKey, ti.txHash, ti.outputIndex))
}
