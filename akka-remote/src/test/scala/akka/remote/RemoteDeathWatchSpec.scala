/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor.{ ActorSystem, DeathWatchSpec }
import com.typesafe.config.ConfigFactory

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteDeathWatchSpec extends AkkaSpec(ConfigFactory.parseString("""
akka {
    actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
            /watchers.remote = "tcp.akka://other@localhost:2666"
        }
    }
    remote.netty.tcp {
        hostname = "localhost"
        port = 0
    }
}
                                                                      """)) with ImplicitSender with DefaultTimeout with DeathWatchSpec {

  val other = ActorSystem("other", ConfigFactory.parseString("akka.remote.netty.tcp.port=2666")
    .withFallback(system.settings.config))

  override def beforeTermination() {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.warning(pattern = "received dead letter.*Disassociate")))
  }

  override def afterTermination() {
    other.shutdown()
  }

}
