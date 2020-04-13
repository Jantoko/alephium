package org.alephium.protocol.config

import org.alephium.util.Duration

trait ConsensusConfig extends GroupConfig {

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: BigInt

  def blockTargetTime: Duration
  def tipsPruneInterval: Int
  def tipsPruneDuration: Duration = blockTargetTime.timesUnsafe(tipsPruneInterval.toLong)
}
