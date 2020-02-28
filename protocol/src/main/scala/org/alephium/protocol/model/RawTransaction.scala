package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.{Keccak256, Keccak256Hash}
import org.alephium.serde._
import org.alephium.util.AVector

case class RawTransaction(inputs: AVector[TxOutputPoint], outputs: AVector[TxOutput])
    extends Keccak256Hash[RawTransaction] {
  override val hash: Keccak256 = _getHash
}

object RawTransaction {
  implicit val serde: Serde[RawTransaction] = new Serde[RawTransaction] {
    val inputsSerde: Serde[AVector[TxOutputPoint]] = serdeImpl[AVector[TxOutputPoint]]
    val outputsSerde: Serde[AVector[TxOutput]]     = serdeImpl[AVector[TxOutput]]

    override def serialize(input: RawTransaction): ByteString = {
      inputsSerde.serialize(input.inputs) ++
        outputsSerde.serialize(input.outputs)
    }

    override def _deserialize(input: ByteString): SerdeResult[(RawTransaction, ByteString)] = {
      for {
        inputsPair <- inputsSerde._deserialize(input)
        inputs = inputsPair._1
        rest1  = inputsPair._2
        outputsPair <- outputsSerde._deserialize(rest1)
        outputs = outputsPair._1
        rest2   = outputsPair._2
      } yield (RawTransaction(inputs, outputs), rest2)
    }
  }
}