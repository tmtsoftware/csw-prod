package csw.params.commands

import org.scalatest.{FunSuite, Matchers}

//DEOPSCSW-205 Return unique id on successful command verification
class IdTest extends FunSuite with Matchers {
/*
  test("should able to create setup commands having unique runId") {

    var runIds: Set[Id] = Set.empty

    // Create 10 setup commands. For each command created, fetch the runId and add it in a Set[RunId].
    // Since Set can contain only unique values, asserting 10 runIds in set determines the uniquely created runId for all 10 setup commands.
    1 to 10 foreach { _ ⇒
      val setup = Setup(Prefix(""), CommandName(""), Some(ObsId("Obs001")), Set.empty)
      runIds = runIds + setup.runId // runId is available in created setup command
    }

    runIds.size shouldBe 10
  }

  test("should able to create observe commands having unique runId") {

    var runIds: Set[Id] = Set.empty

    // Create 10 observe commands. For each command created, fetch the runId and add it in a Set[RunId].
    // Since Set can contain only unique values, asserting 10 runIds in set determines the uniquely created runId for all 10 observe commands.
    1 to 10 foreach { _ ⇒
      val observe = Observe(Prefix(""), CommandName(""), Some(ObsId("Obs001")), Set.empty)
      runIds = runIds + observe.runId // runId is available in created observe command
    }

    runIds.size shouldBe 10
  }

  test("should able to create wait commands having unique runId") {

    var runIds: Set[Id] = Set.empty

    // Create 10 wait commands. For each command created, fetch the runId and add it in a Set[RunId].
    // Since Set can contain only unique values, asserting 10 runIds in set determines the uniquely created runId for all 10 wait commands.
    1 to 10 foreach { _ ⇒
      val wait = Wait(Prefix(""), CommandName(""), Some(ObsId("Obs001")), Set.empty)
      runIds = runIds + wait.runId // runId is available in created wait command
    }

    runIds.size shouldBe 10
  }
  */
}
