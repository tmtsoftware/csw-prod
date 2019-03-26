package csw.params.commands

import csw.params.core.generics.KeyType.ByteKey
import csw.params.core.generics._
import csw.params.core.models.Units.{degree, meter, NoUnits}
import csw.params.core.models.{ArrayData, ObsId, Prefix}
import org.scalatest.FunSpec

import scala.util.Try

// DEOPSCSW-183: Configure attributes and values
// DEOPSCSW-185: Easy to Use Syntax/Api
// DEOPSCSW-196: Command Payloads for variable command content
// DEOPSCSW-320: Add command type in Setup, observe and wait
class CommandsTest extends FunSpec {

  private val ck1         = "wfos.prog.cloudcover"
  private val ck3         = "wfos.red.detector"
  private val commandName = CommandName("move")

  private val obsId: ObsId = ObsId("Obs001")
  describe("Setup config tests") {
    val k1    = KeyType.IntKey.make("encoder")
    val k2    = KeyType.StringKey.make("stringThing")
    val k2bad = KeyType.IntKey.make("stringThing")
    val k3    = KeyType.IntKey.make("notUsed")

    it("Should allow adding keys using single set") {
      val i1  = k1.set(22)
      val i2  = k2.set("A")
      val sc1 = Setup(Prefix(ck3), commandName, Some(obsId)).add(i1).add(i2)
      assert(sc1.size == 2)
      assert(sc1.exists(k1))
      assert(sc1.exists(k2))
      assert(!sc1.exists(k2bad))

      // Validation of the correct type needs to be done with concrete types, outside the generic API!
      assert(Try(sc1(k1)).isSuccess)
      assert(Try(sc1(k2)).isSuccess)

      assert(Try(sc1(k2bad)).isFailure)
      assert(Try(sc1.get(k2bad).get).isFailure)

      assert(sc1.get(k1).head == i1)
      assert(sc1.get(k2).head == i2)
      assert(sc1.missingKeys(k1, k2, k3) == Set(k3.keyName))

      assert(sc1.commandName == commandName)
    }

    // DEOPSCSW-190: Implement Unit Support
    it("Should allow setting with units") {
      var sc1 = Setup(Prefix(ck3), commandName, Some(obsId))
      sc1 = sc1.madd(k1.set(22).withUnits(degree), k2.set("B"))
      assert(sc1.size == 2)
      assert(sc1.exists(k1))
      assert(sc1.exists(k2))
      assert(sc1.get(k1).map(_.units == degree).get)
      assert(sc1.get(k2).get.units == NoUnits)
    }

    it("Should allow apply which returns values") {
      var sc1 = Setup(Prefix(ck3), commandName, Some(obsId))
      sc1 = sc1.madd(k1.set(22).withUnits(degree), k2.set("C"))

      val v1: Parameter[Int]    = sc1(k1)
      val v2: Parameter[String] = sc1(k2)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(v1.values === Array(22))
      assert(v2.values === Array("C"))
      assert(sc1(k2)(0) == "C")
    }

    // DEOPSCSW-190: Implement Unit Support
    it("should update for the same key with set") {
      var sc1 = Setup(Prefix(ck3), commandName, Some(obsId))
      sc1 = sc1.add(k2.set("D"))
      assert(sc1.exists(k2))
      assert(sc1(k2).values === Array("D"))

      sc1 = sc1.add(k2.set("E").withUnits(meter))
      assert(sc1.exists(k2))
      assert(sc1(k2).units == meter)
      assert(sc1(k2).values === Array("E"))
    }

    // DEOPSCSW-186: Binary value payload
    it("Should able to create with Byte Parameteret") {
      val byteKey1 = ByteKey.make("byteKey1")
      val byteKey2 = ByteKey.make("byteKey2")
      val bytes1   = Array[Byte](10, 20)
      val bytes2   = Array[Byte](30, 40)

      val i1 = byteKey1.set(bytes1)
      val i2 = byteKey2.set(bytes2)

      val sc1 = Setup(Prefix(ck3), commandName, Some(obsId), Set(i1, i2))
      assert(sc1.size == 2)
      assert(sc1.exists(byteKey1))
      assert(sc1.exists(byteKey2))
      assert(!sc1.exists(k2bad))

      assert(sc1.get(byteKey1).head == i1)
      assert(sc1.get(byteKey2).head == i2)
      assert(sc1.missingKeys(byteKey1, byteKey2, k3) == Set(k3.keyName))
    }

    // DEOPSCSW-190: Implement Unit Support
    it("Should allow updates") {
      val i1 = k1.set(22)
      assert(i1.head == 22)
      assert(i1.units == NoUnits)
      val i2 = k1.set(33)
      assert(i2.head == 33)
      assert(i2.units == NoUnits)

      var sc = Setup(Prefix(ck3), commandName, Some(obsId)).add(i1)
      // Use option
      assert(sc.get(k1).get == i1)
      assert(sc.get(k1).get.head == 22)
      // Use direct
      assert(sc(k1).values === Array(22))
      assert(sc(k1).value(0) == 22)
      sc = sc.add(i2)
      assert(sc(k1).head == 33)
    }

    // DEOPSCSW-315: Make ObsID optional in commands
    it("Should allow to create setup without obsId") {
      val i1 = k1.set(22)

      val sc = Setup(Prefix(ck1), commandName, None).add(i1)
      // Use option
      assert(sc.get(k1).get == i1)
      assert(sc.get(k1).get.head == 22)
      assert(sc.maybeObsId.isEmpty)
    }

    // DEOPSCSW-369: Unique runId for commands
    it("Should create new RunId when a parameter is added or removed") {
      val i1 = k1.set(22)

      val sc1 = Setup(Prefix(ck3), commandName, Some(obsId))

      val mutatedSc1 = sc1.add(i1)

      val mutatedSc2 = mutatedSc1.remove(i1)

      assert(sc1.source == mutatedSc1.source)
      assert(sc1.commandName == mutatedSc1.commandName)
      assert(sc1.maybeObsId == mutatedSc1.maybeObsId)

      assert(mutatedSc2.source == mutatedSc1.source)
      assert(mutatedSc2.commandName == mutatedSc1.commandName)
      assert(mutatedSc2.maybeObsId == mutatedSc1.maybeObsId)
    }
  }

  describe("Observe config tests") {

    val k1 = KeyType.IntKey.make("repeat")
    val k2 = KeyType.IntKey.make("expTime")
    it("Should allow adding keys") {
      val i1  = k1.set(22)
      val i2  = k2.set(44)
      val oc1 = Observe(Prefix(ck3), commandName, Some(obsId)).add(i1).add(i2)
      assert(oc1.size == 2)
      assert(oc1.exists(k1))
      assert(oc1.exists(k2))
      assert(oc1(k1).head == 22)
      assert(oc1.get(k2).get.head == 44)

      assert(oc1.commandName == commandName)
    }

    // DEOPSCSW-315: Make ObsID optional in commands
    it("Should allow setting") {
      var oc1 = Observe(Prefix(ck3), commandName, Some(obsId))
      oc1 = oc1.add(k1.set(22)).add(k2.set(44))
      assert(oc1.size == 2)
      assert(oc1.maybeObsId.contains(obsId))
      assert(oc1.exists(k1))
      assert(oc1.exists(k2))
    }

    it("Should allow apply") {
      var oc1 = Observe(Prefix(ck3), commandName, Some(obsId))
      oc1 = oc1.add(k1.set(22)).add(k2.set(44))

      val v1 = oc1(k1)
      val v2 = oc1(k2)
      assert(oc1.get(k1).isDefined)
      assert(oc1.get(k2).isDefined)
      assert(v1.values === Array(22))
      assert(v2.head == 44)
    }

    it("should update for the same key with set") {
      var oc1 = Observe(Prefix(ck3), commandName, Some(obsId))
      oc1 = oc1.add(k2.set(22))
      assert(oc1.exists(k2))
      assert(oc1(k2).values === Array(22))

      oc1 = oc1.add(k2.set(33))
      assert(oc1.exists(k2))
      assert(oc1(k2).values === Array(33))
    }

    it("should update for the same key with add") {
      var oc1 = Observe(Prefix(ck3), commandName, Some(obsId))
      oc1 = oc1.add(k2.set(22).withUnits(NoUnits))
      assert(oc1.exists(k2))
      assert(oc1(k2).values === Array(22))

      oc1 = oc1.add(k2.set(33).withUnits(NoUnits))
      assert(oc1.exists(k2))
      assert(oc1(k2).values === Array(33))
    }

    // DEOPSCSW-369: Unique runId for commands
    it("Should create new RunId when a parameter is added or removed") {
      val i1 = k1.set(22)

      val oc1 = Observe(Prefix(ck3), commandName, Some(obsId))

      val mutatedOc1 = oc1.add(i1)

      val mutatedOc2 = mutatedOc1.remove(i1)

      assert(oc1.source == mutatedOc1.source)
      assert(oc1.commandName == mutatedOc1.commandName)
      assert(oc1.maybeObsId == mutatedOc1.maybeObsId)

      assert(mutatedOc2.source == mutatedOc1.source)
      assert(mutatedOc2.commandName == mutatedOc1.commandName)
      assert(mutatedOc2.maybeObsId == mutatedOc1.maybeObsId)
    }
  }

  describe("Wait config tests") {

    val k1 = KeyType.IntKey.make("repeat")
    val k2 = KeyType.IntKey.make("expTime")
    it("Should allow adding keys") {
      val i1  = k1.set(22)
      val i2  = k2.set(44)
      val wc1 = Wait(Prefix(ck3), commandName, Some(obsId)).add(i1).add(i2)
      assert(wc1.size == 2)
      assert(wc1.exists(k1))
      assert(wc1.exists(k2))
      assert(wc1(k1).head == 22)
      assert(wc1.get(k2).get.head == 44)

      assert(wc1.commandName == commandName)
    }

    // DEOPSCSW-315: Make ObsID optional in commands
    it("Should allow setting") {
      var wc1 = Wait(Prefix(ck3), commandName, Some(obsId))
      wc1 = wc1.add(k1.set(22)).add(k2.set(44))
      assert(wc1.size == 2)
      assert(wc1.maybeObsId.contains(obsId))
      assert(wc1.exists(k1))
      assert(wc1.exists(k2))
    }

    it("Should allow apply") {
      var wc1 = Wait(Prefix(ck3), commandName, Some(obsId))
      wc1 = wc1.add(k1.set(22)).add(k2.set(44))

      val v1 = wc1(k1)
      val v2 = wc1(k2)
      assert(wc1.get(k1).isDefined)
      assert(wc1.get(k2).isDefined)
      assert(v1.values === Array(22))
      assert(v2.head == 44)
    }

    it("should update for the same key with set") {
      var wc1 = Wait(Prefix(ck3), commandName, Some(obsId))
      wc1 = wc1.add(k2.set(22))
      assert(wc1.exists(k2))
      assert(wc1(k2).values === Array(22))

      wc1 = wc1.add(k2.set(33))
      assert(wc1.exists(k2))
      assert(wc1(k2).values === Array(33))
    }

    it("should update for the same key with add") {
      var wc1 = Wait(Prefix(ck3), commandName, Some(obsId))
      wc1 = wc1.add(k2.set(22).withUnits(NoUnits))
      assert(wc1.exists(k2))
      assert(wc1(k2).values === Array(22))

      wc1 = wc1.add(k2.set(33).withUnits(NoUnits))
      assert(wc1.exists(k2))
      assert(wc1(k2).values === Array(33))
    }

    // DEOPSCSW-369: Unique runId for commands
    it("Should create new RunId when a parameter is added or removed") {
      val i1 = k1.set(22)

      val wc1 = Wait(Prefix(ck3), commandName, Some(obsId))

      val mutatedWc1 = wc1.add(i1)

      val mutatedWc2 = mutatedWc1.remove(i1)

      assert(wc1.source == mutatedWc1.source)
      assert(wc1.commandName == mutatedWc1.commandName)
      assert(wc1.maybeObsId == mutatedWc1.maybeObsId)

      assert(mutatedWc2.source == mutatedWc1.source)
      assert(mutatedWc2.commandName == mutatedWc1.commandName)
      assert(mutatedWc2.maybeObsId == mutatedWc1.maybeObsId)
    }
  }

  describe("testing for getting typed items") {
    val t1  = KeyType.IntKey.make("test1")
    val sc1 = Setup(Prefix(ck3), commandName, Some(obsId)).add(t1.set(Array(22), degree))

    val item: Option[Parameter[Int]] = sc1.get(t1) // Works now!
    val itm: Parameter[Int]          = item.get
    assert(itm.units == degree)
    val i: Int = itm(0)
    assert(i == 22)
    val i2: Int = itm.head
    assert(i2 == i)
    val i3: Int = sc1(t1).head
    assert(i3 == i)
  }

  describe("Checking for item types in configs") {
    val k1: Key[Int]    = KeyType.IntKey.make("itest")
    val k2: Key[Double] = KeyType.DoubleKey.make("dtest")
    val k3: Key[String] = KeyType.StringKey.make("stest")

    val i1 = k1.set(1, 2, 3).withUnits(degree)
    val i2 = k2.set(1.0, 2.0, 3.0).withUnits(meter)
    val i3 = k3.set("A", "B", "C")

    it("Should get as IntItem") {
      val sc = Setup(Prefix(ck3), commandName, Some(obsId)).add(i1).add(i2).add(i3)

      val out1: Option[Parameter[Int]]    = sc.get(k1)
      val out2: Option[Parameter[Double]] = sc.get(k2)
      val out3: Option[Parameter[String]] = sc.get(k3)

      assert(out1.get.values === Array(1, 2, 3))
      assert(out2.get.values === Array(1.0, 2.0, 3.0))
      assert(out3.get.values === Array("A", "B", "C"))
    }
  }

  describe("Check for multi-add") {
    val k1: Key[Int]    = KeyType.IntKey.make("itest")
    val k2: Key[Double] = KeyType.DoubleKey.make("dtest")
    val k3: Key[String] = KeyType.StringKey.make("stest")

    val i1 = k1.set(1, 2, 3).withUnits(degree)
    val i2 = k2.set(1.0, 2.0, 3.0).withUnits(meter)
    val i3 = k3.set("A", "B", "C")

    it("Should allow vararg add") {
      val sc = Setup(Prefix(ck3), commandName, Some(obsId)).madd(i1, i2, i3)
      assert(sc.size == 3)
      assert(sc.exists(k1))
      assert(sc.exists(k2))
      assert(sc.exists(k3))
    }
  }

  describe("Should work with remove") {
    val k1 = KeyType.IntKey.make("itest")
    val k2 = KeyType.DoubleKey.make("dtest")
    val k3 = KeyType.StringKey.make("stest")
    val k4 = KeyType.LongArrayKey.make("lartest")

    val i1 = k1.set(1, 2, 3).withUnits(degree)
    val i2 = k2.set(1.0, 2.0, 3.0).withUnits(meter)
    val i3 = k3.set("A", "B", "C")
    val i4 = k4.set(ArrayData(Array.fill[Long](100)(10)), ArrayData(Array.fill[Long](100)(100)))

    it("Setup command should allow removing one at a time") {
      var sc1 = Setup(Prefix(ck3), commandName, Some(obsId)).madd(i1, i2, i3, i4)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k2)
      assert(sc1.size == 2)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k3)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isDefined)

      // Should allow removing non-existent
      sc1 = sc1.remove(k3)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k4)
      assert(sc1.size == 0)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isEmpty)

      // Add allows re-adding
      sc1 = sc1.add(i4)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isDefined)
    }

    it("Observe command should allow removing one at a time") {
      var sc1 = Observe(Prefix(ck3), commandName, Some(obsId)).madd(i1, i2, i3, i4)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      // Should allow removing non-existent
      sc1 = sc1.remove(k1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      // Add allows re-adding
      sc1 = sc1.add(i1)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)
    }

    it("Wait command should allow removing one at a time") {
      var sc1 = Wait(Prefix(ck3), commandName, Some(obsId)).madd(i1, i2, i3, i4)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(k1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      // Should allow removing non-existent
      sc1 = sc1.remove(k1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      // Add allows re-adding
      sc1 = sc1.add(i1)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      assert(sc1.commandName == commandName)
    }
  }

  describe("should work with remove by item") {
    val k1 = KeyType.IntKey.make("itest")
    val k2 = KeyType.DoubleKey.make("dtest")
    val k3 = KeyType.StringKey.make("stest")
    val k4 = KeyType.LongArrayKey.make("lartest")

    val i1  = k1.set(1, 2, 3).withUnits(degree)
    val i11 = k1.set(1, 2, 3).withUnits(degree) // This is here to see if it is checking equality or address
    val i2  = k2.set(1.0, 2.0, 3.0).withUnits(meter)
    val i3  = k3.set("A", "B", "C")
    val i4  = k4.set(ArrayData(Array.fill[Long](100)(10)), ArrayData(Array.fill[Long](100)(100)))
    val i5  = k1.set(22) // This is not added for testing not present removal

    it("Should allow removing one at a time") {
      var sc1 = Setup(Prefix(ck3), commandName, Some(obsId)).madd(i1, i2, i3, i4)
      assert(sc1.size == 4)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(i1)
      assert(sc1.size == 3)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isDefined)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(i2)
      assert(sc1.size == 2)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isDefined)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(i3)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isDefined)

      // Should allow removing non-existent
      sc1 = sc1.remove(i5)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isDefined)

      sc1 = sc1.remove(i4)
      assert(sc1.size == 0)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isEmpty)

      // Add allows re-adding
      sc1 = sc1.add(i1)
      assert(sc1.size == 1)
      assert(sc1.get(k1).isDefined)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isEmpty)

      // Here we are removing with an item identical ot i1, but different address to check
      // if it is removing on address or contents (should be latter)
      sc1 = sc1.remove(i11)
      assert(sc1.size == 0)
      assert(sc1.get(k1).isEmpty)
      assert(sc1.get(k2).isEmpty)
      assert(sc1.get(k3).isEmpty)
      assert(sc1.get(k4).isEmpty)
    }
  }
}
