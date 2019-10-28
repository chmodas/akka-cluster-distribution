/*
 * Copyright (c) 2019 Borislav Borisov
 */

package akka.cluster.distribution

import akka.actor.PoisonPill
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, _}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.typed.{Cluster, Leave}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import scala.concurrent.duration._


sealed trait ConsumerCommand
final case class EnsureActive(consumerId: String, replyTo: ActorRef[AcknowledgeActive]) extends ConsumerCommand

sealed trait ConsumerResponse
final case class AcknowledgeActive(consumerId: String) extends ConsumerResponse
final case class Get(replyTo: ActorRef[Set[String]]) extends ConsumerResponse

object Consumer {

  def empty(entityId: String): Behavior[ConsumerCommand] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case EnsureActive(consumerId, replyTo) =>
          replyTo ! AcknowledgeActive(s"$consumerId|${ctx.self.path.name}")
          Behaviors.stopped
      }
    }
  }

}

object Verifier {
  def actor(consumers: Set[String]): Behavior[ConsumerResponse] = {
    Behaviors.receiveMessage {
      case AcknowledgeActive(consumerId) =>
        actor(consumers + consumerId)
      case Get(replyTo) =>
        replyTo ! consumers
        Behaviors.same
    }
  }
}

class ClusterDistributionApiSpec extends FlatSpecLike with Matchers with Eventually with BeforeAndAfterAll {
  // akka.cluster.jmx.multi-mbeans-in-same-jvm is turned on to remove
  // the "Could not unregister Cluster JMX Bean with name" warning,
  // caused by the abrupt PoisonPill shutdown of the shard region below
  private val conf = ConfigFactory.parseString(
    """
      |akka.actor.provider = cluster
      |akka.remote.netty.tcp.hostname=127.0.0.1
      |akka.remote.netty.tcp.port=2555
      |akka.http.server.preview.enable-http2 = on
      |akka.cluster.seed-nodes = ["akka.tcp://ClusterDistributionApiSpec@127.0.0.1:2555"]
      |akka.coordinated-shutdown.terminate-actor-system = off
      |akka.coordinated-shutdown.run-by-actor-system-terminate = off
      |akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      |akka.cluster.run-coordinated-shutdown-when-down = off
      |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
    """.stripMargin)
      .withFallback(ConfigFactory.defaultApplication())

  private val testKit = ActorTestKit("ClusterDistributionApiSpec", conf)
  private implicit val testKitSettings: TestKitSettings = TestKitSettings(testKit.system)

  override def afterAll: Unit = {
    super.afterAll()
    testKit.shutdownTestKit()
  }

  "ClusterDistribution" should "send periodic 'ensure active' messages to the distributed entities" in {
    val probe = testKit.createTestProbe[Set[String]]()
    val verifier = testKit.spawn(Verifier.actor(Set()), "Verifier")

    val typeName = "Consumer"
    val ensureActiveMessage = (consumerId: String) => EnsureActive(consumerId, verifier)
    val ensureActiveInterval = 1.second
    val distribution = ClusterDistribution(testKit.system)
    distribution.start(typeName, Consumer.empty, ensureActiveMessage, ensureActiveInterval, Set("a", "b", "c"))

    eventually(timeout(5.seconds.dilated)) {
      verifier ! Get(probe.ref)
      val consumers = probe.expectMessageType[Set[String]]
      consumers should contain allOf("a|Consumer-a", "b|Consumer-b", "c|Consumer-c")
    }

  }

  it should "terminate if the shard region is stopped" in {
    val probe = testKit.createTestProbe[ConsumerResponse]()

    val typeName = "DeadConsumer"
    val ensureActiveMessage = (consumerId: String) => EnsureActive(consumerId, probe.ref)
    val ensureActiveInterval = 1.second
    val distribution = ClusterDistribution(testKit.system)

//    val shardRegion = distribution.start(typeName, Consumer.empty, ensureActiveMessage, ensureActiveInterval, Set("a", "b", "c"))
//    testKit.system.toClassic.actorSelection(shardRegion.path).tell(PoisonPill, probe.ref.toClassic)

    // FIXME: Using the Leave appears to trigger a coordinated shutdown, even though it is turned off. Go figure...
    // https://doc.akka.io/docs/akka/current/actors.html#coordinated-shutdown
    distribution.start(typeName, Consumer.empty, ensureActiveMessage, ensureActiveInterval, Set("a", "b", "c"))
    Cluster(testKit.system).manager ! Leave(Cluster(testKit.system).selfMember.address)

    probe.expectNoMessage(5.seconds)

  }

}
