package csw

/**
 * == Messages ==
 *
 * This project is intended to hold reusable models and messages used throughout the csw source code.
 *
 * This also provides out of the box support to cater to the diverse communication requirements.
 * Consumer of this library will be able to create Commands, Events, States to store ParameterSets.
 *
 * == Imp Packages ==
 *
 * === Commands and Events ===
 *
 * This packages contains classes, traits and models used to create *commands* and *events*.
 * These are all based on type-safe keys and items (a set of values with optional units).
 * Each key has a specific type and the key's values must be of that type.
 *
 * Two types of [[csw.params.commands.Command]] are supported:
 *   - [[csw.params.commands.SequenceCommand]]
 *     - This commands are targeted to Sequencer. Subtypes of this are: Setup, Observe and Wait.
 *
 *   - [[csw.params.commands.ControlCommand]]
 *     - This commands are targeted to Assemblies and HCD's. Subtypes of this are: Setup and Observe.
 *
 * Following are the concrete commands supported by csw:
 *  - [[csw.params.commands.Setup]]
 *  - [[csw.params.commands.Observe]]
 *  - [[csw.params.commands.Wait]]
 *
 * Two types of [[csw.params.events.Event]] are supported:
 *  - [[csw.params.events.SystemEvent]]
 *  - [[csw.params.events.ObserveEvent]]
 *
 * Another important feature provided by *commands* package is [[csw.params.commands.matchers.Matcher]]
 * One of the use case for using matcher is when Assembly sends [[csw.params.CommandMessage.Oneway]] command to HCD
 * and in response to this command HCD keeps publishing its current state.
 * Then Assembly can use Matcher with the matching definition as provided by [[csw.params.commands.matchers.StateMatcher]] to
 * match against the current states published by HCD.
 *
 * === Location and Framework ===
 *
 * These packages contain reusable classes, traits and models. We are keeping all the models which are getting transferred over the wire and
 * requires serialization and deserialization in `csw-messages` project. All the models are marked with [[csw.params.TMTSerializable]].
 * [[csw.params.TMTSerializable]] is a marker trait which extends [[scala.Serializable]]. This is configured to use `kryo` serialization.
 * Also these models are being shared between multiple projects. `csw-location`, `csw-framework` and `csw-logging` depends on `csw-messages` project
 * which uses these models.
 *
 * Location Service uses [[csw.services.location.api.models.Connection]] model to register component/container of type:
 *   - [[csw.services.location.api.models.ComponentType.Assembly]]
 *   - [[csw.services.location.api.models.ComponentType.HCD]]
 *   - [[csw.services.location.api.models.ComponentType.Service]]
 *   - [[csw.services.location.api.models.ComponentType.Container]]
 *
 * When you resolve/find a [[csw.services.location.api.models.Connection]], you get [[csw.services.location.api.models.Location]] in return which can be one of below type:
 *   - [[csw.services.location.api.models.AkkaLocation]]
 *   - [[csw.services.location.api.models.TcpLocation]]
 *   - [[csw.services.location.api.models.HttpLocation]]
 *
 * Framework package contains following actor messages:
 *  - Messages of type [[csw.params.framework.PubSub]] are supported by PubSubActor
 *  - Below Lifecycle messages can be sent to component when component is in [[csw.params.framework.SupervisorLifecycleState.Running]] state,
 *  note that these messages should be wrapped inside [[csw.params.RunningMessage.Lifecycle]] before sending it to Supervisor actor.
 *   - [[csw.params.framework.ToComponentLifecycleMessages.GoOnline]]
 *   - [[csw.params.framework.ToComponentLifecycleMessages.GoOffline]]
 *
 * === Params ===
 *
 * This package supports serialization and deserialization of commands, events and state variables in following formats:
 *  - JSON : [[csw.params.core.formats.JsonSupport]]
 *  - Kryo : [[csw.params.core.generics.ParamSetSerializer]]
 *  - Protobuf : package pb contains utility and directory protobuf contains proto schema files.
 *
 * === Scala and Java APIs ===
 *
 * All the param and event classes are immutable. The `set` methods return a new instance of the object with a
 * new item added and the `get` methods return an Option, in case the Key is not found. There are also `value` methods
 * that return a value directly, throwing an exception if the key or value is not found.
 *
 * === Key Types ===
 *
 * A set of standard key types and matching items are defined. Each key accepts one or more values
 * of the given type.
 *
 * Following [[csw.params.core.generics.KeyType]] are supported by csw:
 *
 * {{{
 *
 *       +--------------+-------------------------+---------------------------+
 *       |  Primitive   |      Scala KeyType      |       Java KeyType        |
 *       +--------------+-------------------------+---------------------------+
 *       | Boolean      | KeyType.BooleanKey      | JKeyTypes.BooleanKey      |
 *       | Character    | KeyType.CharKey         | JKeyTypes.JCharKey        |
 *       | Byte         | KeyType.ByteKey         | JKeyTypes.ByteKey         |
 *       | Short        | KeyType.ShortKey        | JKeyTypes.ShortKey        |
 *       | Long         | KeyType.LongKey         | JKeyTypes.LongKey         |
 *       | Int          | KeyType.IntKey          | JKeyTypes.IntKey          |
 *       | Float        | KeyType.FloatKey        | JKeyTypes.FloatKey        |
 *       | Double       | KeyType.DoubleKey       | JKeyTypes.DoubleKey       |
 *       | String       | KeyType.StringKey       | JKeyTypes.StringKey       |
 *       | Timestamp    | KeyType.TimestampKey    | JKeyTypes.TimestampKey    |
 *       | ----------   | ----------              | ----------                |
 *       | ByteArray    | KeyType.ByteArrayKey    | JKeyTypes.ByteArrayKey    |
 *       | ShortArray   | KeyType.ShortArrayKey   | JKeyTypes.ShortArrayKey   |
 *       | LongArray    | KeyType.LongArrayKey    | JKeyTypes.LongArrayKey    |
 *       | IntArray     | KeyType.IntArrayKey     | JKeyTypes.IntArrayKey     |
 *       | FloatArray   | KeyType.FloatArrayKey   | JKeyTypes.FloatArrayKey   |
 *       | DoubleArray  | KeyType.DoubleArrayKey  | JKeyTypes.DoubleArrayKey  |
 *       | ----------   | ----------              | ----------                |
 *       | ByteMatrix   | KeyType.ByteMatrixKey   | JKeyTypes.ByteMatrixKey   |
 *       | ShortMatrix  | KeyType.ShortMatrixKey  | JKeyTypes.ShortMatrixKey  |
 *       | LongMatrix   | KeyType.LongMatrixKey   | JKeyTypes.LongMatrixKey   |
 *       | IntMatrix    | KeyType.IntMatrixKey    | JKeyTypes.IntMatrixKey    |
 *       | FloatMatrix  | KeyType.FloatMatrixKey  | JKeyTypes.FloatMatrixKey  |
 *       | DoubleMatrix | KeyType.DoubleMatrixKey | JKeyTypes.DoubleMatrixKey |
 *       | ----------   | ----------              | ----------                |
 *       | Choice       | KeyType.ChoiceKey       | JKeyTypes.ChoiceKey       |
 *       | RaDec        | KeyType.RaDecKey        | JKeyTypes.RaDecKey        |
 *       | Struct       | KeyType.StructKey       | JKeyTypes.StructKey       |
 *       +--------------+-------------------------+---------------------------+
 *
 * }}}
 *
 * Detailed information about creating Keys and Parameters can be found here:
 *  https://tmtsoftware.github.io/csw-prod/services/messages/keys-parameters.html
 *
 * Detailed information about creating commands can be found here:
 *  https://tmtsoftware.github.io/csw-prod/services/messages/commands.html
 *
 * Detailed information about creating events can be found here:
 *  https://tmtsoftware.github.io/csw-prod/services/messages/events.html
 */
package object params {}