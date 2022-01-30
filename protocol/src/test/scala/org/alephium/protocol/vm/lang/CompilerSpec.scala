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

package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.protocol.{Hash, Signature, SignatureSchema}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off no.equal file.size.limit
class CompilerSpec extends AlephiumSpec with ContextGenerators {
  it should "parse asset script" in {
    val script =
      s"""
         |// comment
         |AssetScript Foo {
         |  pub fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin
    Compiler.compileAssetScript(script).isRight is true
  }

  it should "parse tx script" in {
    {
      info("success")

      val script =
        s"""
           |// comment
           |TxScript Foo {
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileTxScript(script).isRight is true
    }

    {
      info("fail with event definition")

      val script =
        s"""
           |TxScript Foo {
           |  event Add(a: U256, b: U256)
           |
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileTxScript(script)
        .leftValue
        .message is """Parser failed: Parsed.Failure(Position 3:3, found "event Add(")"""
    }
  }

  it should "parse contracts" in {
    {
      info("success")

      val contract =
        s"""
           |// comment
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |  // comment
           |  pub fn add0(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |
           |  fn add1() -> (U256) {
           |    return (x + y)
           |  }
           |
           |  fn add2(d: U256) -> () {
           |    let mut z = 0u
           |    z = d
           |    x = x + z // comment
           |    y = y + z // comment
           |    return
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is true
    }

    {
      info("no function definition")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |  event Add(a: U256, b: U256)
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "No function definition in TxContract Foo"
    }

    {
      info("duplicated function definitions")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |  pub fn add1(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add2(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add3(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |
           |  pub fn add1(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add2(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "These functions are defined multiple times: add1, add2"
    }

  }

  it should "infer types" in {
    def check(
        xMut: String,
        a: String,
        aType: String,
        b: String,
        bType: String,
        rType: String,
        fname: String,
        validity: Boolean = false
    ) = {
      val contract =
        s"""
         |TxContract Foo($xMut x: U256) {
         |  pub fn add($a: $aType, $b: $bType) -> ($rType) {
         |    x = a + b
         |    return (a - b)
         |  }
         |
         |  fn $fname() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
      Compiler.compileContract(contract).isRight is validity
    }

    check("mut", "a", "U256", "b", "U256", "U256", "foo", true)
    check("", "a", "U256", "b", "U256", "U256", "foo")
    check("mut", "x", "U256", "b", "U256", "U256", "foo")
    check("mut", "a", "U256", "x", "U256", "U256", "foo")
    check("mut", "a", "I64", "b", "U256", "U256", "foo")
    check("mut", "a", "U256", "b", "I64", "U256", "foo")
    check("mut", "a", "U256", "b", "U256", "I64", "foo")
    check("mut", "a", "U256", "b", "U256", "U256, U256", "foo")
    check("mut", "a", "U256", "b", "U256", "U256", "add")
  }

  it should "parse multiple contracts" in {
    val input =
      s"""
         |TxContract Foo() {
         |  fn foo(bar: Bar) -> () {
         |    return bar.bar()
         |  }
         |
         |  pub fn bar() -> () {
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  pub fn bar() -> () {
         |    return foo()
         |  }
         |
         |  fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(input, 0).isRight is true
    Compiler.compileTxScript(input, 1).isRight is true
  }

  trait Fixture {
    def test(
        input: String,
        args: AVector[Val],
        output: AVector[Val] = AVector.empty,
        fields: AVector[Val] = AVector.empty
    ): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, fields)
      StatefulVM.executeWithOutputs(context, obj, args) isE output
    }
  }

  it should "generate IR code" in new Fixture {
    val input =
      s"""
         |TxContract Foo(x: U256) {
         |
         |  pub fn add(a: U256) -> (U256) {
         |    return square(x) + square(a)
         |  }
         |
         |  fn square(n: U256) -> (U256) {
         |    return n * n
         |  }
         |}
         |""".stripMargin

    test(
      input,
      AVector(Val.U256(U256.Two)),
      AVector(Val.U256(U256.unsafe(5))),
      AVector(Val.U256(U256.One))
    )
  }

  it should "verify signature" in {
    def input(hash: Hash) =
      s"""
         |AssetScript P2PKH {
         |  pub fn verify(pk: ByteVec) -> () {
         |    let hash = #${hash.toHexString}
         |    assert!(hash == blake2b!(pk))
         |    verifyTxSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin

    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    val pubKeyHash       = Hash.hash(pubKey.bytes)

    val script = Compiler.compileAssetScript(input(pubKeyHash)).rightValue
    deserialize[StatelessScript](serialize(script)) isE script

    val args             = AVector[Val](Val.ByteVec.from(pubKey))
    val statelessContext = genStatelessContext(signatures = AVector(Signature.zero))
    val signature        = SignatureSchema.sign(statelessContext.txId.bytes, priKey)
    statelessContext.signatures.pop().rightValue is Signature.zero
    statelessContext.signatures.push(signature) isE ()
    StatelessVM.execute(statelessContext, script.toObject, args).isRight is true
    StatelessVM.execute(statelessContext, script.toObject, args) is
      failed(StackUnderflow) // no signature in the stack
  }

  it should "converse values" in new Fixture {
    test(
      s"""
         |TxContract Conversion() {
         |  pub fn main() -> () {
         |    let mut x = 5u
         |    let mut y = 5i
         |    x = u256!(y)
         |    y = i256!(x)
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )
  }

  it should "test while" in new Fixture {
    test(
      s"""
         |TxContract While() {
         |  pub fn main() -> (U256) {
         |    let mut x = 5
         |    let mut done = false
         |    while !done {
         |      x = x + x - 3
         |      if x % 5 == 0 { done = true }
         |    }
         |    return x
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector(Val.U256(U256.unsafe(35)))
    )
  }

  it should "test the following typical examples" in new Fixture {
    test(
      s"""
         |TxContract Main() {
         |
         |  pub fn main() -> () {
         |    let an_i256 = 5i
         |    let an_u256 = 5u
         |
         |    // Or a default will be used.
         |    let default_integer = 7   // `U256`
         |
         |    // A mutable variable's value can be changed.
         |    let mut another_i256 = 5i
         |    let mut another_u256 = 5u
         |    another_i256 = 6i
         |    another_u256 = 6u
         |
         |    let mut bool = true
         |    bool = false
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  pub fn f(n: I256) -> (I256) {
         |    if n < 2i {
         |      return n
         |    } else {
         |      return f(n-1i) + f(n-2i)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I256(I256.from(10))),
      AVector[Val](Val.I256(I256.from(55)))
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  pub fn f(n: U256) -> (U256) {
         |    if n < 2u {
         |      return n
         |    } else {
         |      return f(n-1u) + f(n-2u)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(10))),
      AVector[Val](Val.U256(U256.unsafe(55)))
    )

    test(
      s"""
         |TxContract Test() {
         |  pub fn main() -> (Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool) {
         |    let b0 = 1 == 1
         |    let b1 = 1 == 2
         |    let b2 = 1 != 2
         |    let b3 = 1 != 1
         |    let b4 = 1 < 2
         |    let b5 = 1 < 0
         |    let b6 = 1 <= 2
         |    let b7 = 1 <= 1
         |    let b8 = 1 > 0
         |    let b9 = 1 > 2
         |    let b10 = 1 >= 0
         |    let b11 = 1 >= 1
         |    return b0, b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector[Val](
        Val.True,
        Val.False,
        Val.True,
        Val.False,
        Val.True,
        Val.False,
        Val.True,
        Val.True,
        Val.True,
        Val.False,
        Val.True,
        Val.True
      )
    )

    test(
      s"""
         |TxContract Foo() {
         |  pub fn f(mut n: U256) -> (U256) {
         |    if n < 2 {
         |      n = n + 1
         |    }
         |    return n
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(2))),
      AVector[Val](Val.U256(U256.unsafe(2)))
    )
  }

  it should "execute quasi uniswap" in new Fixture {
    val contract =
      s"""
         |TxContract Uniswap(
         |  mut alphReserve: U256,
         |  mut btcReserve: U256
         |) {
         |  pub fn exchange(alphAmount: U256) -> (U256) {
         |    let tokenAmount = btcReserve * alphAmount / (alphReserve + alphAmount)
         |    alphReserve = alphReserve + alphAmount
         |    btcReserve = btcReserve - tokenAmount
         |    return tokenAmount
         |  }
         |}
         |""".stripMargin

    test(
      contract,
      AVector(Val.U256(U256.unsafe(1000))),
      AVector(Val.U256(U256.unsafe(99))),
      AVector(Val.U256(U256.unsafe(1000000)), Val.U256(U256.unsafe(100000)))
    )

    test(
      contract,
      AVector(Val.U256(U256.unsafe(1000))),
      AVector(Val.U256(U256.unsafe(99))),
      AVector(
        Val.U256(U256.unsafe(Long.MaxValue) divUnsafe U256.unsafe(10)),
        Val.U256(U256.unsafe(Long.MaxValue) divUnsafe U256.unsafe(100))
      )
    )
  }

  it should "test operator precedence" in new Fixture {
    val contract =
      s"""
         |TxContract Operator() {
         |  pub fn main() -> (U256, Bool, Bool) {
         |    let x = 1 + 2 * 3 - 2 / 2
         |    let y = (1 < 2) && (2 <= 2) && (2 < 3)
         |    let z = !false && false || false
         |
         |    return x, y, z
         |  }
         |}
         |""".stripMargin
    test(contract, AVector.empty, AVector(Val.U256(U256.unsafe(6)), Val.True, Val.False))
  }

  it should "compile array failed" in {
    val codes = List(
      s"""
         |// duplicated variable name
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = 0
         |    let x = [1, 2, 3]
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// duplicated variable name
         |TxContract Foo(x: [U256; 2]) {
         |  fn foo() -> () {
         |    let x = [2; 3]
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// assign to immutable array element(contract field)
         |TxContract Foo(x: [U256; 2]) {
         |  fn set() -> () {
         |    x[0] = 2
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// assign to immutable array element(local variable)
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 4]
         |    x[0] = 3
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// out of index
         |TxContract Foo() {
         |  fn foo() -> (U256) {
         |    let x = [[2; 2]; 4]
         |    return x[1][3]
         |  }
         |}
         |""".stripMargin,
      s"""
         |// out of index
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [2; 2]
         |    x[2] = 3
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid array element assignment
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2]
         |    x[2] = 2
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid array element assignment
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2]
         |    x[0][0] = 2
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid array expression
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = [1, 2]
         |    let y = x[0][0]
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid array expression
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = 2
         |    let y = x[0]
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid binary expression(compare array)
         |TxContract Foo() {
         |  fn foo() -> (Bool) {
         |    let x = [3; 2]
         |    let y = [3; 2]
         |    return x == y
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid binary expression(add array)
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 2] + [2; 2]
         |    return
         |  }
         |}""".stripMargin,
      s"""
         |// assign array element with invalid type
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [3i; 2]
         |    x[0] = 3
         |    return
         |  }
         |}
         |""".stripMargin
    )
    codes.foreach(code => Compiler.compileContract(code).isLeft is true)
  }

  trait ArrayTestFixture {
    def code: String
    def fields: AVector[Val]

    lazy val contract       = Compiler.compileContract(code).rightValue
    lazy val (obj, context) = prepareContract(contract, fields)

    def test(methodIndex: Int, args: AVector[Val], result: AVector[Val]) = {
      StatefulVM.executeWithOutputs(context, obj, args, methodIndex).rightValue is result
    }
  }

  it should "test array" in new ArrayTestFixture {
    val code =
      s"""
         |TxContract ArrayTest() {
         |  pub fn test0() -> (Bool) {
         |    let mut arr1 = [1, 2, 3]
         |    arr1[0] = 2
         |    return arr1[0] == 2 && arr1[1] == 2 && arr1[2] == 3
         |  }
         |
         |  pub fn test1(x: U256) -> (U256) {
         |    return [x; 4][0]
         |  }
         |
         |  pub fn test2(mut x: [Bool; 4]) -> (Bool) {
         |    x[1] = !x[1]
         |    return x[1]
         |  }
         |
         |  pub fn test3(mut x: [U256; 4]) -> (Bool) {
         |    let mut y = x
         |    y[0] = y[0] + 1
         |    return y[0] == (x[0] + 1)
         |  }
         |
         |  pub fn test4(x: U256) -> ([U256; 2]) {
         |    let y = [[x; 2]; 5]
         |    return y[0]
         |  }
         |
         |  pub fn test5(value: U256) -> ([[U256; 2]; 2]) {
         |    let x = [[value; 2]; 3]
         |    let mut y = x
         |    y[0][0] = y[0][0] + 1
         |    y[0][1] = y[0][1] + 1
         |    y[2][0] = y[2][0] + 2
         |    y[2][1] = y[2][1] + 2
         |    return [y[0], y[2]]
         |  }
         |
         |  pub fn foo(x: U256) -> ([U256; 4]) {
         |    return [x; 4]
         |  }
         |
         |  pub fn test7(x: U256) -> (U256) {
         |    return foo(x)[0]
         |  }
         |
         |  pub fn bar(x: [U256; 4], y: [U256; 4]) -> (U256) {
         |    return [x, y][0][0]
         |  }
         |
         |  pub fn test9(x: U256) -> (U256) {
         |    return bar(foo(x), foo(x))
         |  }
         |
         |  pub fn test10() -> (Bool) {
         |    let mut x = [[4; 2]; 2]
         |    let y = [3; 2]
         |    x[0] = y
         |    return x[0][0] == 3 &&
         |           x[0][1] == 3 &&
         |           x[1][0] == 4 &&
         |           x[1][1] == 4
         |  }
         |
         |  pub fn test11() -> (Bool) {
         |    let mut x = [[[4; 2]; 2]; 2]
         |    let y = [3; 2]
         |    x[1][1] = y
         |    return x[0][0][0] == 4 &&
         |           x[0][0][1] == 4 &&
         |           x[1][1][0] == 3 &&
         |           x[1][1][1] == 3
         |  }
         |
         |  pub fn test12() -> (Bool) {
         |    let mut x = [4; 2]
         |    let y = [x, x]
         |    x[0] = 3
         |    return y[0][0] == 4 &&
         |           y[0][1] == 4 &&
         |           y[1][0] == 4 &&
         |           y[1][1] == 4
         |  }
         |
         |  pub fn test13() -> (Bool) {
         |    let mut x = [[4; 2]; 2]
         |    let y = [x[0], x[1]]
         |    x[0] = [3; 2]
         |    return y[0][0] == 4 &&
         |           y[0][1] == 4 &&
         |           y[1][0] == 4 &&
         |           y[1][1] == 4 &&
         |           x[0][0] == 3 &&
         |           x[0][1] == 3
         |  }
         |}
         |""".stripMargin

    val fields: AVector[Val] = AVector.empty

    test(0, AVector.empty, AVector(Val.Bool(true)))
    test(1, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(2, AVector.fill(4)(Val.Bool(true)), AVector(Val.Bool(false)))
    test(3, AVector.fill(4)(Val.U256(10)), AVector(Val.Bool(true)))
    test(4, AVector(Val.U256(4)), AVector.fill(2)(Val.U256(4)))
    test(5, AVector(Val.U256(1)), AVector(Val.U256(2), Val.U256(2), Val.U256(3), Val.U256(3)))
    test(7, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(9, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(10, AVector.empty, AVector(Val.Bool(true)))
    test(11, AVector.empty, AVector(Val.Bool(true)))
    test(12, AVector.empty, AVector(Val.Bool(true)))
    test(13, AVector.empty, AVector(Val.Bool(true)))
  }

  it should "test contract array fields" in new ArrayTestFixture {
    val code =
      s"""
         |TxContract Foo(
         |  arr0: [U256; 2],
         |  mut arr1: [[U256; 2]; 4],
         |  mut x: U256
         |) {
         |  pub fn getArr0() -> ([U256; 2]) {
         |    return arr0
         |  }
         |
         |  pub fn getArr1() -> ([[U256; 2]; 4]) {
         |    return arr1
         |  }
         |
         |  pub fn setArr1(arr: [[U256; 2]; 4]) -> () {
         |    arr1 = arr
         |    return
         |  }
         |
         |  pub fn add() -> ([U256; 4]) {
         |    x = x + 1
         |    return [x + 1, x + 2, x + 3, x + 4]
         |  }
         |
         |  pub fn addTest1() -> (U256) {
         |    let res = [add(), add(), add(), add()]
         |    return x
         |  }
         |
         |  pub fn addTest2() -> (U256) {
         |    let res = [add(), add(), add(), add()][2]
         |    return x
         |  }
         |
         |  pub fn addTest3() -> (U256) {
         |    let res = [add(); 4]
         |    return x
         |  }
         |
         |  pub fn addTest4() -> (U256) {
         |    let res = add()
         |    return x
         |  }
         |
         |  pub fn test8() -> (U256) {
         |    let a = add()[1]
         |    return a
         |  }
         |}
         |""".stripMargin

    val fields: AVector[Val] = AVector.fill(11)(Val.U256(7))
    test(0, AVector.empty, AVector.fill(2)(Val.U256(7)))
    test(1, AVector.empty, AVector.fill(8)(Val.U256(7)))
    test(2, AVector.fill(8)(Val.U256(3)), AVector.empty)
    test(1, AVector.empty, AVector.fill(8)(Val.U256(3)))
    test(4, AVector.empty, AVector(Val.U256(11)))
    test(5, AVector.empty, AVector(Val.U256(15)))
    test(6, AVector.empty, AVector(Val.U256(19)))
    test(7, AVector.empty, AVector(Val.U256(20)))
    test(8, AVector.empty, AVector(Val.U256(23)))
  }

  it should "generate efficient code for arrays" in {
    val code =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    let mut x = [1, 2, 3, 4]
         |    let y = x[0]
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(code).rightValue.methods.head is
      Method[StatefulContext](
        isPublic = true,
        isPayable = false,
        argsLength = 0,
        localsLength = 5,
        returnLength = 0,
        instrs = AVector[Instr[StatefulContext]](
          U256Const1,
          U256Const2,
          U256Const3,
          U256Const4,
          StoreLocal(4),
          StoreLocal(3),
          StoreLocal(2),
          StoreLocal(1),
          LoadLocal(1),
          StoreLocal(0)
        )
      )
  }

  it should "parse events definition and emission" in {

    {
      info("event definition and emission")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is true
    }

    {
      info("multiple event definitions and emissions")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |
           |  event Add1(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add1(a, b)
           |    emit Add2(a, b)
           |    return (a + b)
           |  }
           |
           |  event Add2(a: U256, b: U256)
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is true
    }

    {
      info("event doesn't exist")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add2(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).leftValue.message is "Event Add2 does not exist"
    }

    {
      info("duplicated event definitions")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |
           |  event Add1(a: U256, b: U256)
           |  event Add2(a: U256, b: U256)
           |  event Add3(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add(a, b)
           |    return (a + b)
           |  }
           |
           |  event Add1(b: U256, a: U256)
           |  event Add2(b: U256, a: U256)
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "These events are defined multiple times: Add1, Add2"
    }

    {
      info("emit event with wrong args")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    let z = false
           |    emit Add(a, z)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "Invalid args type List(U256, Bool) for event Add(U256, U256)"
    }
  }
}
