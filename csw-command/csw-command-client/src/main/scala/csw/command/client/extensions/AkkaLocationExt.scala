package csw.command.client.extensions

import akka.actor.typed.{ActorRef, ActorSystem}
import csw.command.client.messages.sequencer.SequencerMsg
import csw.command.client.messages.{ComponentMessage, ContainerMessage}
import csw.location.api.extensions.URIExtension.RichURI
import csw.location.models.AkkaLocation

import scala.reflect.ClassTag

//TODO: Find a better way for Java Support
object AkkaLocationExt {
  implicit class RichAkkaLocation(val akkaLocation: AkkaLocation) {

    private def typedRef[T: ClassTag](implicit actorSystem: ActorSystem[_]): ActorRef[T] = {
      val typeManifest    = scala.reflect.classTag[T].runtimeClass.getSimpleName
      val messageManifest = akkaLocation.connection.componentId.componentType.messageManifest

      require(
        typeManifest == messageManifest,
        s"actorRef for type $messageManifest can not handle messages of type $typeManifest"
      )

      akkaLocation.uri.toActorRef.unsafeUpcast[T]
    }

    /**
     * If the component type is HCD or Assembly, use this to get the correct ActorRef
     *
     * @return a typed ActorRef that understands only ComponentMessage
     */
    def componentRef(implicit actorSystem: ActorSystem[_]): ActorRef[ComponentMessage] = typedRef[ComponentMessage]

    /**
     * If the component type is Container, use this to get the correct ActorRef
     *
     * @return a typed ActorRef that understands only ContainerMessage
     */
    def containerRef(implicit actorSystem: ActorSystem[_]): ActorRef[ContainerMessage] = typedRef[ContainerMessage]

    private[csw] def sequencerRef(implicit actorSystem: ActorSystem[_]): ActorRef[SequencerMsg] =
      typedRef[SequencerMsg]

  }
}
