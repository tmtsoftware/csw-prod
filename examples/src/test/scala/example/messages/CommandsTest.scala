package example.messages

import csw.params.commands.{CommandName, Observe, Setup, Wait}
import csw.params.core.formats.JsonSupport
import csw.params.core.generics.KeyType.{ByteKey, DoubleMatrixKey}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.Units.degree
import csw.params.core.models._
import csw.time.core.models.UTCTime
import org.scalatest.{FunSpec, Matchers}

import scala.util.Try

class CommandsTest extends FunSpec with Matchers {

  //#obsid
  val obsId: ObsId = ObsId("Obs001")
  //#obsid

  describe("Examples of Prefix") {
    it("should show usage of utility functions") {

      //#prefix
      //using constructor, supplying subsystem and prefix both
      val prefix1: Prefix = Prefix("nfiraos.ncc.trombone")

      //just by supplying prefix
      val prefix2: Prefix = Prefix("tcs.mobie.blue.filter")

      //invalid prefix string that cant be mapped to a valid subsystem,
      // will automatically get Subsystem.BAD
      val badPrefix: Prefix = Prefix("abcdefgh")

      //use implicit conversion to convert from String to Prefix
      val prefix3: Prefix = Prefix("wfos.prog.cloudcover")
      //#prefix

      //validations
      assert(prefix1.subsystem === Subsystem.NFIRAOS)
      assert(prefix2.subsystem === Subsystem.TCS)
      assert(prefix3.subsystem === Subsystem.WFOS)
      assert(badPrefix.subsystem === Subsystem.BAD)
    }
  }

  describe("Examples of commands") {

    it("Should show usage of Setup command") {
      //#setup
      //keys
      val k1: Key[Int]    = KeyType.IntKey.make("encoder")
      val k2: Key[String] = KeyType.StringKey.make("stringThing")
      val k2bad: Key[Int] = KeyType.IntKey.make("missingKey")
      val k3: Key[Int]    = KeyType.IntKey.make("filter")
      val k4: Key[Float]  = KeyType.FloatKey.make("correction")

      //prefix
      val prefix: Prefix = Prefix("wfos.red.detector")

      //parameters
      val i1: Parameter[Int]    = k1.set(22)
      val i2: Parameter[String] = k2.set("A")

      //create Setup, add sequentially using add
      val sc1: Setup = Setup(prefix, CommandName("move"), Some(obsId)).add(i1).add(i2)

      //access keys
      val k1Exists: Boolean = sc1.exists(k1) //true

      //access parameters
      val tryParam1: Try[Parameter[Int]] = Try(sc1(k1))    //success
      val tryk2Bad: Try[Parameter[Int]]  = Try(sc1(k2bad)) //failure

      //add more than one parameters, using madd
      val sc2: Setup     = sc1.madd(k3.set(1, 2, 3, 4).withUnits(Units.day), k4.set(1.0f, 2.0f))
      val paramSize: Int = sc2.size

      //add binary payload
      val byteKey1: Key[Byte] = ByteKey.make("byteKey1")
      val byteKey2: Key[Byte] = ByteKey.make("byteKey2")
      val bytes1: Array[Byte] = Array[Byte](10, 20)
      val bytes2: Array[Byte] = Array[Byte](30, 40)

      val b1: Parameter[Byte] = byteKey1.set(bytes1)
      val b2: Parameter[Byte] = byteKey2.set(bytes2)

      val sc3: Setup = Setup(prefix, CommandName("move"), Some(obsId), Set(b1, b2))

      //remove a key
      val sc4: Setup = sc3.remove(b1)

      //list all keys
      val allKeys: Set[String] = sc4.paramSet.map(_.keyName)

      //#setup

      //validations
      assert(k1Exists)
      assert(tryParam1.isSuccess)
      assert(tryk2Bad.isFailure)
      assert(paramSize === 4)
      assert(sc3.size === 2)
      assert(sc4.size === 1)
      assert(allKeys.size === 1)
    }

    it("Should show usage of Observe command") {
      //#observe
      //keys
      val k1: Key[Boolean] = KeyType.BooleanKey.make("repeat")
      val k2: Key[Int]     = KeyType.IntKey.make("expTime")
      val k2bad: Key[Int]  = KeyType.IntKey.make("missingKey")
      val k3: Key[Int]     = KeyType.IntKey.make("filter")
      val k4: Key[UTCTime] = KeyType.UTCTimeKey.make("creation-time")

      //prefix
      val prefix: Prefix = Prefix("wfos.red.detector")

      //parameters
      val i1: Parameter[Boolean] = k1.set(true, false, true, false)
      val i2: Parameter[Int]     = k2.set(1, 2, 3, 4)

      //create Observe, add sequentially using add
      val oc1: Observe = Observe(prefix, CommandName("move"), Some(obsId)).add(i1).add(i2)

      //access parameters using apply method
      val k1Param: Parameter[Boolean] = oc1.get(k1).get //true
      val values: Array[Boolean]      = k1Param.values

      //access parameters
      val tryParam1: Try[Parameter[Boolean]] = Try(oc1(k1))    //success
      val tryk2Bad: Try[Parameter[Int]]      = Try(oc1(k2bad)) //failure

      //add more than one parameters, using madd
      val oc2: Observe   = oc1.madd(k3.set(1, 2, 3, 4).withUnits(Units.day), k4.set(UTCTime.now()))
      val paramSize: Int = oc2.size

      //update existing key with set
      val oc3: Observe = oc1.add(k2.set(5, 6, 7, 8))

      //remove a key
      val oc4: Observe = oc2.remove(k4)

      //#observe

      //validations
      assert(tryParam1.isSuccess)
      assert(tryk2Bad.isFailure)
      assert(paramSize === 4)
      assert(values === Array(true, false, true, false))
      assert(oc3(k2).values === Array(5, 6, 7, 8))
      assert(oc4.size === 3)
    }

    it("Should show usage of Wait command") {
      //#wait
      //keys
      val k1: Key[Boolean] = KeyType.BooleanKey.make("repeat")
      val k2: Key[Int]     = KeyType.IntKey.make("expTime")
      val k2bad: Key[Int]  = KeyType.IntKey.make("missingKey")
      val k3: Key[Int]     = KeyType.IntKey.make("filter")
      val k4: Key[UTCTime] = KeyType.UTCTimeKey.make("creation-time")

      //parameters
      val i1: Parameter[Boolean] = k1.set(true, false, true, false)
      val i2: Parameter[Int]     = k2.set(1, 2, 3, 4)

      //prefix
      val prefix: Prefix = Prefix("wfos.red.detector")

      //create wait, add sequentially using add
      val wc1: Wait = Wait(prefix, CommandName("move"), Some(obsId)).add(i1).add(i2)

      //access params using get method
      val k1Param: Option[Parameter[Boolean]] = wc1.get(k1)
      val values: Array[Boolean]              = k1Param.map(_.values).getOrElse(Array.empty[Boolean])

      //access parameters
      val tryParam1: Try[Parameter[Boolean]] = Try(wc1(k1))    //success
      val tryk2Bad: Try[Parameter[Int]]      = Try(wc1(k2bad)) //failure

      //add more than one parameters, using madd
      val wc2: Wait      = wc1.madd(k3.set(1, 2, 3, 4).withUnits(Units.day), k4.set(UTCTime.now()))
      val paramSize: Int = wc2.size

      //update existing key with set
      val wc3: Wait = wc1.add(k2.set(5, 6, 7, 8))

      //remove a key
      val wc4: Wait = wc2.remove(k4)

      //#wait

      //validations
      assert(tryParam1.isSuccess)
      assert(values === Array(true, false, true, false))
      assert(tryk2Bad.isFailure)
      assert(paramSize === 4)
      assert(wc3(k2).values === Array(5, 6, 7, 8))
      assert(wc4.size === 3)
    }
  }

  describe("Examples of serialization") {
    it("should show reading and writing of commands") {

      //#json-serialization
      import play.api.libs.json.{JsValue, Json}

      //key
      val k1: Key[MatrixData[Double]] = DoubleMatrixKey.make("myMatrix")
      //values
      val m1: MatrixData[Double] = MatrixData.fromArrays(
        Array(1.0, 2.0, 3.0),
        Array(4.1, 5.1, 6.1),
        Array(7.2, 8.2, 9.2)
      )

      //prefix
      val prefix: Prefix = Prefix("wfos.red.detector")

      //parameter
      val i1: Parameter[MatrixData[Double]] = k1.set(m1)

      //commands
      val sc: Setup   = Setup(prefix, CommandName("move"), Some(obsId)).add(i1)
      val oc: Observe = Observe(prefix, CommandName("move"), Some(obsId)).add(i1)
      val wc: Wait    = Wait(prefix, CommandName("move"), Some(obsId)).add(i1)

      //json support - write
      val scJson: JsValue = JsonSupport.writeSequenceCommand(sc)
      val ocJson: JsValue = JsonSupport.writeSequenceCommand(oc)
      val wcJson: JsValue = JsonSupport.writeSequenceCommand(wc)

      //optionally prettify
      val str: String = Json.prettyPrint(scJson)

      //construct command from string
      val scFromPrettyStr = JsonSupport.readSequenceCommand[Setup](Json.parse(str))

      //json support - read
      val sc1: Setup   = JsonSupport.readSequenceCommand[Setup](scJson)
      val oc1: Observe = JsonSupport.readSequenceCommand[Observe](ocJson)
      val wc1: Wait    = JsonSupport.readSequenceCommand[Wait](wcJson)
      //#json-serialization

      //validations
      assert(sc === sc1)
      assert(oc === oc1)
      assert(wc === wc1)
      assert(scFromPrettyStr === sc)
    }
  }

  describe("Examples of unique key constraint") {
    it("should show duplicate keys are removed") {

      //#unique-key
      //keys
      val encoderKey: Key[Int] = KeyType.IntKey.make("encoder")
      val filterKey: Key[Int]  = KeyType.IntKey.make("filter")
      val miscKey: Key[Int]    = KeyType.IntKey.make("misc.")

      //prefix
      val prefix: Prefix = Prefix("wfos.red.detector")

      //params
      val encParam1: Parameter[Int] = encoderKey.set(1)
      val encParam2: Parameter[Int] = encoderKey.set(2)
      val encParam3: Parameter[Int] = encoderKey.set(3)

      val filterParam1: Parameter[Int] = filterKey.set(1)
      val filterParam2: Parameter[Int] = filterKey.set(2)
      val filterParam3: Parameter[Int] = filterKey.set(3)

      val miscParam1 = miscKey.set(100)

      //Setup command with duplicate key via constructor
      val setup: Setup =
        Setup(prefix,
              CommandName("move"),
              Some(obsId),
              Set(encParam1, encParam2, encParam3, filterParam1, filterParam2, filterParam3))
      //four duplicate keys are removed; now contains one Encoder and one Filter key
      val uniqueKeys1 = setup.paramSet.toList.map(_.keyName)

      //try adding duplicate keys via add + madd
      val changedSetup: Setup = setup
        .add(encParam3)
        .madd(
          filterParam1,
          filterParam2,
          filterParam3
        )
      //duplicate keys will not be added. Should contain one Encoder and one Filter key
      val uniqueKeys2: List[String] = changedSetup.paramSet.toList.map(_.keyName)

      //miscKey(unique) will be added; encoderKey(duplicate) will not be added
      val finalSetUp: Setup = setup.madd(Set(miscParam1, encParam1))
      //now contains encoderKey, filterKey, miscKey
      val uniqueKeys3: List[String] = finalSetUp.paramSet.toList.map(_.keyName)
      //#unique-key

      //validations
      uniqueKeys1 should contain theSameElementsAs List(encoderKey.keyName, filterKey.keyName)
      uniqueKeys2 should contain theSameElementsAs List(encoderKey.keyName, filterKey.keyName)
      uniqueKeys3 should contain theSameElementsAs List(encoderKey.keyName, filterKey.keyName, miscKey.keyName)
    }
  }

  // DEOPSCSW-392: Provide a helper method in commands to create unique runId
  describe("Examples of clone command") {
    val k1             = KeyType.IntKey.make("itest")
    val commandName    = CommandName("command-name")
    val prefix: Prefix = Prefix("wfos.red.detector")

    val i1 = k1.set(1, 2, 3).withUnits(degree)
/*
    it("clone command creates a command from existing command with a new RunId for Setup, Observe or Wait") {
      //#clone-command
      val setup  = Setup(prefix, commandName, Some(obsId)).madd(i1)
      val setup2 = setup.cloneCommand

      val observe  = Observe(prefix, commandName, Some(obsId)).madd(i1)
      val observe2 = observe.cloneCommand

      val wait  = Wait(prefix, commandName, Some(obsId)).madd(i1)
      val wait2 = wait.cloneCommand
      //#clone-command

      assert(setup.runId != setup2.runId)
      assert(setup.commandName == setup2.commandName)
      assert(setup.maybeObsId == setup2.maybeObsId)
      assert(setup.source == setup2.source)
      assert(setup.paramSet == setup2.paramSet)

      assert(observe.runId != observe2.runId)
      assert(observe.commandName == observe2.commandName)
      assert(observe.maybeObsId == observe2.maybeObsId)
      assert(observe.source == observe2.source)
      assert(observe.paramSet == observe2.paramSet)

      assert(wait.runId != wait2.runId)
      assert(wait.commandName == wait2.commandName)
      assert(wait.maybeObsId == wait2.maybeObsId)
      assert(wait.source == wait2.source)
      assert(wait.paramSet == wait2.paramSet)
    }
    */
  }
}
