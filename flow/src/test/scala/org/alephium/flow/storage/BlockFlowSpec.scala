package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, AlephiumSpec, Hex}
import org.scalatest.{Assertion, BeforeAndAfter}

import scala.annotation.tailrec

class BlockFlowSpec extends AlephiumSpec with PlatformConfig.Default with BeforeAndAfter {
  behavior of "BlockFlow"

  before {
    DiskIO.createDir(config.diskIO.blockFolder).isRight is true
  }

  after {
    TestUtils.cleanup(config.diskIO.blockFolder)
  }

  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow()
    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) is 0
    }
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1)
      blockFlow.getWeight(block1) is 1

      val chainIndex2 = ChainIndex(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header)
      blockFlow.getWeight(block2.header) is 2

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 3

      val chainIndex4 = ChainIndex(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4)
      blockFlow.getWeight(block4) is 4
    }
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks1.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 1
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 1
        }
      }

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks2.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 4
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 4
        }
      }

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex(i, j))
      newBlocks3.foreach { block =>
        val index = block.chainIndex
        if (index.from == GroupIndex(0) || index.to == GroupIndex(0)) {
          addAndCheck(blockFlow, block)
          blockFlow.getWeight(block) is 8
        } else {
          addAndCheck(blockFlow, block.header)
          blockFlow.getWeight(block.header) is 8
        }
      }
    }
  }

  it should "work for 2 user group when there is forks" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow()

      val chainIndex1 = ChainIndex(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11)
      addAndCheck(blockFlow, block12)
      blockFlow.getWeight(block11) is 1
      blockFlow.getWeight(block12) is 1

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13)
      blockFlow.getWeight(block13) is 2

      val chainIndex2 = ChainIndex(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header)
      addAndCheck(blockFlow, block22.header)
      blockFlow.getWeight(block21) is 3
      blockFlow.getWeight(block22) is 3

      val chainIndex3 = ChainIndex(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3)
      blockFlow.getWeight(block3) is 4
    }
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val deps = blockFlow.getBestDepsUnsafe(chainIndex).deps

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, AVector.empty[Transaction], config.maxMiningTarget, nonce)
      if (chainIndex.validateDiff(block)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.add(block) is AddBlockResult.Success
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader): Assertion = {
    blockFlow.add(header) is AddBlockHeaderResult.Success
  }

  def show(blockFlow: BlockFlow): String = {
    blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeight(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val hash   = showHash(tip)
        val deps   = header.blockDeps.map(showHash).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: $hash, deps: $deps"
      }
      .mkString("\n")
  }

  def showHash(hash: Keccak256): String = {
    Hex.toHexString(hash.bytes).take(8)
  }
}
