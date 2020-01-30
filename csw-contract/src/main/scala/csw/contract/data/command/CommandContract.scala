package csw.contract.data.command

import csw.command.api.messages.CommandServiceHttpMessage.{Oneway, Query, Submit, Validate}
import csw.command.api.messages.CommandServiceWebsocketMessage.{QueryFinal, SubscribeCurrentState}
import csw.contract.generator.models.ClassNameHelpers.name
import csw.contract.generator.models.DomHelpers._
import csw.contract.generator.models.{Endpoint, ModelType}
import csw.params.commands.CommandResponse._
import csw.params.commands._
import csw.params.core.generics.{KeyType, Parameter}
import csw.params.core.models.Units
import csw.params.core.states.CurrentState
import enumeratum.EnumEntry

object CommandContract extends CommandData {
  val models: Map[String, ModelType] = Map(
    name[ControlCommand]     -> ModelType(observe, setup),
    name[CommandName]        -> ModelType(commandName),
    name[Parameter[Int]]     -> ModelType(param),
    name[KeyType[EnumEntry]] -> ModelType(KeyType),
    name[Units]              -> ModelType(Units),
    name[Result]             -> ModelType(result),
    name[SubmitResponse]     -> ModelType(cancelled, completed, error, invalid, locked, started),
    name[OnewayResponse]     -> ModelType(accepted, invalid, locked),
    name[ValidateResponse]   -> ModelType(accepted, invalid, locked),
    name[CurrentState]       -> ModelType(currentState),
    name[CommandIssue] -> ModelType(
      assemblyBusyIssue,
      idNotAvailableIssue,
      missingKeyIssue,
      parameterValueOutOfRangeIssue,
      requiredAssemblyUnavailableIssue,
      requiredSequencerUnavailableIssue,
      requiredServiceUnavailableIssue,
      requiredHCDUnavailableIssue,
      unresolvedLocationsIssue,
      unsupportedCommandInStateIssue,
      unsupportedCommandIssue,
      wrongInternalStateIssue,
      wrongNumberOfParametersIssue,
      wrongParameterTypeIssue,
      wrongPrefixIssue,
      wrongUnitsIssue,
      otherIssue
    )
  )

  val httpEndpoints = Map(
    name[Validate] -> Endpoint(observeValidate, name[ValidateResponse]),
    name[Submit]   -> Endpoint(observeSubmit, name[SubmitResponse]),
    name[Query]    -> Endpoint(queryFinal, name[SubmitResponse]),
    name[Oneway]   -> Endpoint(observeOneway, name[OnewayResponse])
  )

  val webSocketsEndpoints = Map(
    name[QueryFinal]            -> Endpoint(queryFinal, name[SubmitResponse]),
    name[SubscribeCurrentState] -> Endpoint(subscribeState, name[CurrentState])
  )
}