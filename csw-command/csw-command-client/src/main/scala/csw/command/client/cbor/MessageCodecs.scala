package csw.command.client.cbor

import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.serialization.{Serialization, SerializationExtension}
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.CommandSerializationMarker.RemoteMsg
import csw.command.client.messages.ComponentCommonMessage.{
  ComponentStateSubscription,
  StartedCommandSubscription,
  GetSupervisorLifecycleState,
  LifecycleStateSubscription
}
import csw.command.client.messages.ContainerCommonMessage.{GetComponents, GetContainerLifecycleState}
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorContainerCommonMessages.{Restart, Shutdown}
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.messages._
import csw.command.client.messages.sequencer.SubmitSequenceAndWait
import csw.command.client.models.framework.LockingResponse._
import csw.command.client.models.framework.PubSub.{Publish, PublisherMessage, SubscriberMessage}
import csw.command.client.models.framework.{PubSub, _}
import csw.location.models.codecs.LocationCodecs
import csw.logging.models.codecs.LoggingCodecs
import csw.params.core.formats.ParamCodecs
import io.bullet.borer.derivation.ArrayBasedCodecs.deriveUnaryCodec
import io.bullet.borer.derivation.MapBasedCodecs._
import io.bullet.borer.{Codec, Decoder, Encoder}

import scala.concurrent.duration.FiniteDuration

trait MessageCodecs extends ParamCodecs with LoggingCodecs with LocationCodecs {

  implicit def actorSystem: ActorSystem[_]

  implicit def actorRefCodec[T]: Codec[ActorRef[T]] =
    Codec.bimap[String, ActorRef[T]](
      actorRef => Serialization.serializedActorPath(actorRef.toUntyped),
      path => {
        val provider = SerializationExtension(actorSystem.toUntyped).system.provider
        provider.resolveActorRef(path)
      }
    )

  implicit def subscribeMessageCodec[T: Encoder: Decoder]: Codec[PubSub.Subscribe[T]] = deriveCodec[PubSub.Subscribe[T]]
  implicit def subscribeOnlyMessageCodec[T: Encoder: Decoder]: Codec[PubSub.SubscribeOnly[T]] =
    deriveCodec[PubSub.SubscribeOnly[T]]
  implicit def unsubscribeMessageCodec[T: Encoder: Decoder]: Codec[PubSub.Unsubscribe[T]] = deriveCodec[PubSub.Unsubscribe[T]]
  implicit def subscriberMessageCodec[T: Encoder: Decoder]: Codec[SubscriberMessage[T]]   = deriveCodec[SubscriberMessage[T]]
  implicit def publishCodec[T: Encoder: Decoder]: Codec[Publish[T]]                       = deriveCodec[Publish[T]]
  implicit def publisherMessageCodec[T: Encoder: Decoder]: Codec[PublisherMessage[T]]     = deriveCodec[PublisherMessage[T]]
  implicit def pubSubCodec[T: Encoder: Decoder]: Codec[PubSub[T]]                         = deriveCodec[PubSub[T]]

  implicit lazy val durationCodec: Codec[FiniteDuration] =
    Codec.bimap[(Long, String), FiniteDuration](
      finiteDuration => (finiteDuration.length, finiteDuration.unit.toString),
      { case (length, unitStr) => FiniteDuration(length, TimeUnit.valueOf(unitStr)) }
    )

  // ************************ LockingResponse Codecs ********************

  implicit lazy val lockReleasedCodec: Codec[LockReleased.type]               = singletonCodec(LockReleased)
  implicit lazy val lockExpiringShortlyCodec: Codec[LockExpiringShortly.type] = singletonCodec(LockExpiringShortly)
  implicit lazy val lockExpiredCodec: Codec[LockExpired.type]                 = singletonCodec(LockExpired)
  implicit lazy val lockAlreadyReleasedCodec: Codec[LockAlreadyReleased.type] = singletonCodec(LockAlreadyReleased)
  implicit lazy val lockAcquiredCodec: Codec[LockAcquired.type]               = singletonCodec(LockAcquired)
  implicit lazy val releasingLockFailedCodec: Codec[ReleasingLockFailed]      = deriveUnaryCodec[ReleasingLockFailed]
  implicit lazy val acquiringLockFailedCodec: Codec[AcquiringLockFailed]      = deriveUnaryCodec[AcquiringLockFailed]
  implicit lazy val lockingResponseCodec: Codec[LockingResponse]              = deriveCodec[LockingResponse]

  // ************************ Components Codecs ********************

  implicit lazy val locationServiceUsageCodec: Codec[LocationServiceUsage] = enumCodec[LocationServiceUsage]
  implicit lazy val componentInfoCodec: Codec[ComponentInfo]               = deriveCodec[ComponentInfo]
  implicit lazy val componentCodec: Codec[Component]                       = deriveCodec[Component]
  implicit lazy val componentsCodec: Codec[Components]                     = deriveCodec[Components]

  // ************************ RemoteMsg Codecs ********************

  implicit lazy val shutdownCodec: Codec[Shutdown.type] = singletonCodec(Shutdown)
  implicit lazy val restartCodec: Codec[Restart.type]   = singletonCodec(Restart)
  implicit lazy val submitCodec: Codec[Submit]          = deriveCodec[Submit]
  implicit lazy val oneWayCodec: Codec[Oneway]          = deriveCodec[Oneway]
  implicit lazy val validateCodec: Codec[Validate]      = deriveCodec[Validate]
  implicit lazy val lockCodec: Codec[Lock]              = deriveCodec[Lock]
  implicit lazy val unlockCodec: Codec[Unlock]          = deriveCodec[Unlock]
  implicit lazy val lifecycleCodec: Codec[Lifecycle]    = deriveCodec[Lifecycle]

  implicit lazy val lifecycleStateChangedCodec: Codec[LifecycleStateChanged]           = deriveCodec[LifecycleStateChanged]
  implicit lazy val lifecycleStateSubscriptionCodec: Codec[LifecycleStateSubscription] = deriveCodec[LifecycleStateSubscription]

  implicit lazy val containerLifecycleStateCodec: Codec[ContainerLifecycleState]          = enumCodec[ContainerLifecycleState]
  implicit lazy val supervisorLifecycleStateCodec: Codec[SupervisorLifecycleState]        = enumCodec[SupervisorLifecycleState]
  implicit lazy val toComponentLifecycleMessagesCodec: Codec[ToComponentLifecycleMessage] = enumCodec[ToComponentLifecycleMessage]

  implicit lazy val getContainerLifecycleStateCodec: Codec[GetContainerLifecycleState] = deriveCodec[GetContainerLifecycleState]
  implicit lazy val getSupervisorLifecycleStateCodec: Codec[GetSupervisorLifecycleState] =
    deriveCodec[GetSupervisorLifecycleState]
  implicit lazy val setComponentLogLevelCodec: Codec[SetComponentLogLevel]             = deriveCodec[SetComponentLogLevel]
  implicit lazy val getComponentLogMetadataCodec: Codec[GetComponentLogMetadata]       = deriveCodec[GetComponentLogMetadata]
  implicit lazy val getComponentsCodec: Codec[GetComponents]                           = deriveCodec[GetComponents]
  implicit lazy val logControlMessageCodec: Codec[LogControlMessage]                   = deriveCodec[LogControlMessage]
  implicit lazy val componentStateSubscriptionCodec: Codec[ComponentStateSubscription] = deriveCodec[ComponentStateSubscription]
  implicit lazy val startedCommandSubscriptionCodec: Codec[StartedCommandSubscription] = deriveCodec[StartedCommandSubscription]
  implicit lazy val messageRemoteMsgCodec: Codec[RemoteMsg]                            = deriveCodec[RemoteMsg]

  // ************************ SequencerMsg Codecs ********************

  implicit lazy val submitSequenceAndWaitCodec: Codec[SubmitSequenceAndWait] = deriveCodec[SubmitSequenceAndWait]
}
