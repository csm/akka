/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import DeploymentConfig._
import Actor._
import Status._
import akka.event.EventHandler
import akka.util.duration._
import akka.config.ConfigurationException
import akka.AkkaException
import RemoteProtocol._
import RemoteDaemonMessageType._
import akka.serialization.{ Serialization, Serializer, ActorSerialization, Compression }
import Compression.LZF

import java.net.InetSocketAddress

import com.google.protobuf.ByteString

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider extends ActorRefProvider {

  private val failureDetector = new BannagePeriodFailureDetector(timeToBan = 60 seconds) // FIXME make timeToBan configurable

  def actorOf(props: Props, address: String): Option[ActorRef] = {
    Address.validate(address)

    val actorRef = Actor.remote.actors.get(address)
    if (actorRef ne null) Some(actorRef)
    else {
      Deployer.lookupDeploymentFor(address) match {
        case Some(Deploy(_, _, router, _, RemoteScope(host, port))) ⇒
          // FIXME create RoutedActorRef if 'router' is specified

          val serverAddress = Remote.address
          if (serverAddress.getHostName == host && serverAddress.getPort == port) {
            // home node for this remote actor
            Some(new LocalActorRef(props, address, false)) // create a local actor
          } else {
            // not home node, need to provision it
            val remoteAddress = new InetSocketAddress(host, port)
            useActorOnNode(remoteAddress, address, props.creator)
            Some(RemoteActorRef(remoteAddress, address, Actor.TIMEOUT, None)) // create a remote actor
          }

        case deploy ⇒ None // non-remote actor
      }
    }
  }

  def findActorRef(address: String): Option[ActorRef] = throw new UnsupportedOperationException

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(remoteAddress: InetSocketAddress, actorAddress: String, actorFactory: () ⇒ Actor) {
    EventHandler.debug(this, "Instantiating Actor [%s] on node [%s]".format(actorAddress, remoteAddress))

    val actorFactoryBytes =
      Serialization.serialize(actorFactory) match {
        case Left(error) ⇒ throw error
        case Right(bytes) ⇒
          if (Remote.shouldCompressData) LZF.compress(bytes)
          else bytes
      }

    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorAddress(actorAddress)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory =
      () ⇒ Remote.server.actorFor(
        Remote.remoteDaemonServiceName, remoteAddress.getHostName, remoteAddress.getPort)

    // try to get the connection for the remote address, if not already there then create it
    val connection = failureDetector.putIfAbsent(remoteAddress, connectionFactory)

    sendCommandToRemoteNode(connection, command, withACK = true) // ensure we get an ACK on the USE command
  }

  private def sendCommandToRemoteNode(
    connection: ActorRef,
    command: RemoteDaemonMessageProtocol,
    withACK: Boolean) {

    if (withACK) {
      try {
        (connection ? (command, Remote.remoteDaemonAckTimeout)).as[Status] match {
          case Some(Success(receiver)) ⇒
            EventHandler.debug(this, "Remote command sent to [%s] successfully received".format(receiver))

          case Some(Failure(cause)) ⇒
            EventHandler.error(cause, this, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote command to [%s] timed out".format(connection.address))
            EventHandler.error(error, this, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, "Could not send remote command to [%s] due to: %s".format(connection.address, e.toString))
          throw e
      }
    } else {
      connection ! command
    }
  }
}