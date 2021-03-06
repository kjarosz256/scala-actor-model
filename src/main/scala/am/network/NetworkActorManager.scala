package am.network

import am._
import am.message.{KillMessage, Message}
import com.typesafe.scalalogging.Logger

import scala.collection.mutable

/**
 * This class is responsible for managing multiple actors.
 * It also provides a network interface to communicate between
 * actors across the network.
 *
 * The manager itself runs in its own thread.
 */
class NetworkActorManager(
  private val ip: String,
  private val port: Int) extends AbstractActorManager {
  private def logger = NetworkActorManager.logger

  /**
   * Used for sending and receiving messages.
   */
  private val server = new UDPServer(ip, port)
  private val registered = new mutable.HashMap[Int, Actor]()
  private var nextId = 0

  private val thread = new Thread(
    () => while (!Thread.interrupted()) handlePacket())
  thread.start()

  def this(ip: String) = this(ip, 0)

  def this() = this("0.0.0.0", 0)

  /**
   * The given actor is registered in this manager and is given a unique
   * network address.
   */
  override def register(actor: Actor): Unit = {
    val id = nextId
    nextId += 1

    registered.put(id, actor)
    actor.reference = referenceAddress(new NetworkActorAddress(server.socketAddress, id))

    actor.registered(this)
  }

  private def handlePacket(): Unit = {
    val packet = server.receive()

    val receiver = registered.get(packet.to.id).orNull
    if (receiver == null) {
      logger.error("While handling a packet: invalid receiver")
      return
    }

    val sender = referenceAddress(packet.from)

    packet.contents match {
      case m: Message => receiver.dispatch(sender, m)
      case _ => logger.error("While handling a packet: invalid message")
    }
  }

  override def referenceAddress(address: ActorAddress): ActorRef = address match {
    case address: NetworkActorAddress => new NetworkActorRef(server, address)
    case _ => super.referenceAddress(address)
  }

  override def shutdown(): Unit = {
    // stop listening to
    thread.interrupt()
    registered.foreach({
      case (_, actor) => actor.dispatch(Actor.ignore, KillMessage())
    })
    server.close()

    super.shutdown()
  }
}

object NetworkActorManager {
  private val logger = Logger("NetworkActorManager")
}
