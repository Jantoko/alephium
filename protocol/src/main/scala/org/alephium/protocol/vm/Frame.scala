// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.protocol.Hash
import org.alephium.util.{AVector, Bytes}

abstract class Frame[Ctx <: Context] {
  var pc: Int
  def obj: ContractObj[Ctx]
  def opStack: Stack[Val]
  def method: Method[Ctx]
  def locals: Array[Val]
  def returnTo: AVector[Val] => ExeResult[Unit]
  def ctx: Ctx

  def balanceStateOpt: Option[BalanceState]

  def pcMax: Int = method.instrs.length

  def advancePC(): Unit = pc += 1

  def offsetPC(offset: Int): ExeResult[Unit] = {
    val newPC = pc + offset
    if (newPC >= 0 && newPC < method.instrs.length) {
      pc = newPC
      okay
    } else {
      failed(InvalidInstrOffset)
    }
  }

  def complete(): Unit = pc = method.instrs.length

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def popT[T <: Val](): ExeResult[T] =
    pop().flatMap { elem =>
      try Right(elem.asInstanceOf[T])
      catch {
        case _: ClassCastException => failed(InvalidType(elem))
      }
    }

  def getLocal(index: Int): ExeResult[Val] = {
    if (locals.isDefinedAt(index)) Right(locals(index)) else failed(InvalidLocalIndex)
  }

  def setLocal(index: Int, v: Val): ExeResult[Unit] = {
    if (!locals.isDefinedAt(index)) {
      failed(InvalidLocalIndex)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    val fields = obj.fields
    if (fields.isDefinedAt(index)) Right(fields(index)) else failed(InvalidFieldIndex)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    val fields = obj.fields
    if (!fields.isDefinedAt(index)) {
      failed(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      failed(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  protected def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index).toRight(Right(InvalidMethodIndex(index)))
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]]

  def callLocal(index: Byte): ExeResult[Option[Frame[Ctx]]] = {
    advancePC()
    for {
      _     <- ctx.chargeGas(GasSchedule.callGas)
      frame <- methodFrame(Bytes.toPosInt(index))
    } yield Some(frame)
  }

  def externalMethodFrame(contractKey: Hash, index: Int): ExeResult[Frame[StatefulContext]]

  def execute(): ExeResult[Option[Frame[Ctx]]]
}

final class StatelessFrame(
    var pc: Int,
    val obj: ContractObj[StatelessContext],
    val opStack: Stack[Val],
    val method: Method[StatelessContext],
    val locals: Array[Val],
    val returnTo: AVector[Val] => ExeResult[Unit],
    val ctx: StatelessContext
) extends Frame[StatelessContext] {
  def methodFrame(index: Int): ExeResult[Frame[StatelessContext]] = {
    for {
      method <- getMethod(index)
      args   <- opStack.pop(method.argsType.length)
      _      <- method.check(args)
    } yield Frame.stateless(ctx, obj, method, args, opStack, opStack.push)
  }

  // Should not be used in stateless context
  def balanceStateOpt: Option[BalanceState]                                                 = ???
  def externalMethodFrame(contractKey: Hash, index: Int): ExeResult[Frame[StatefulContext]] = ???

  @tailrec
  override def execute(): ExeResult[Option[Frame[StatelessContext]]] = {
    if (pc < pcMax) {
      method.instrs(pc) match {
        case CallLocal(index) => callLocal(index)
        case Return           => runReturn()
        case instr            =>
          // No flatMap for tailrec
          instr.runWith(this) match {
            case Right(_) =>
              advancePC()
              execute()
            case Left(e) => Left(e)
          }
      }
    } else if (pc == pcMax) {
      runReturn()
    } else {
      failed(PcOverflow)
    }
  }

  private def runReturn(): ExeResult[Option[Frame[StatelessContext]]] =
    Return.runWith(this).map(_ => None)
}

final class StatefulFrame(
    var pc: Int,
    val obj: ContractObj[StatefulContext],
    val opStack: Stack[Val],
    val method: Method[StatefulContext],
    val locals: Array[Val],
    val returnTo: AVector[Val] => ExeResult[Unit],
    val ctx: StatefulContext,
    val balanceStateOpt: Option[BalanceState]
) extends Frame[StatefulContext] {
  private def getNewFrameBalancesState(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext]
  ): ExeResult[Option[BalanceState]] = {
    if (method.isPayable) {
      for {
        state <- balanceStateOpt.toRight(Right(EmptyBalanceForPayableMethod))
        balanceStateOpt <- {
          val newFrameBalances = state.useApproved()
          contractObj.addressOpt match {
            case Some(contractId) =>
              ctx
                .useContractAsset(contractId)
                .map { balancesPerLockup =>
                  newFrameBalances.remaining.add(LockupScript.p2c(contractId), balancesPerLockup)
                  Some(newFrameBalances)
                }
            case None =>
              Right(Some(newFrameBalances))
          }
        }
      } yield balanceStateOpt
    } else {
      Right(None)
    }
  }

  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method             <- getMethod(index)
      args               <- opStack.pop(method.argsType.length)
      _                  <- method.check(args)
      newBalanceStateOpt <- getNewFrameBalancesState(obj, method)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, obj, method, args, opStack, opStack.push)
    }
  }

  override def externalMethodFrame(
      contractKey: Hash,
      index: Int
  ): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj        <- ctx.loadContract(contractKey)
      method             <- contractObj.getMethod(index).toRight(Right(InvalidMethodIndex(index)))
      _                  <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      args               <- opStack.pop(method.argsType.length)
      _                  <- method.check(args)
      newBalanceStateOpt <- getNewFrameBalancesState(contractObj, method)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, contractObj, method, args, opStack, opStack.push)
    }
  }

  def callExternal(index: Byte): ExeResult[Option[Frame[StatefulContext]]] = {
    advancePC()
    for {
      _           <- ctx.chargeGas(GasSchedule.callGas)
      byteVec     <- popT[Val.ByteVec]()
      contractKey <- Hash.from(byteVec.a).toRight(Right(InvalidContractAddress))
      newFrame    <- externalMethodFrame(contractKey, Bytes.toPosInt(index))
    } yield Some(newFrame)
  }

  @tailrec
  override def execute(): ExeResult[Option[Frame[StatefulContext]]] = {
    if (pc < pcMax) {
      method.instrs(pc) match {
        case CallLocal(index)    => callLocal(index)
        case CallExternal(index) => callExternal(index)
        case Return              => runReturn()
        case instr               =>
          // No flatMap for tailrec
          instr.runWith(this) match {
            case Right(_) =>
              advancePC()
              execute()
            case Left(e) => Left(e)
          }
      }
    } else if (pc == pcMax) {
      runReturn()
    } else {
      failed(PcOverflow)
    }
  }

  private def runReturn(): ExeResult[Option[Frame[StatefulContext]]] =
    for {
      _ <- Return.runWith(this)
    } yield None
}

object Frame {
  def stateless(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatelessContext] = {
    val locals = Array.fill[Val](method.localsLength)(Val.False)
    method.argsType.foreachWithIndex { case (tpe, index) =>
      locals(index) = tpe.default
    }
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatelessFrame(0, obj, operandStack.remainingStack(), method, locals, returnTo, ctx)
  }

  def stateful(
      ctx: StatefulContext,
      balanceStateOpt: Option[BalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] = {
    val locals = Array.fill[Val](method.localsLength)(Val.False)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatefulFrame(
      0,
      obj,
      operandStack.remainingStack(),
      method,
      locals,
      returnTo,
      ctx,
      balanceStateOpt
    )
  }
}
