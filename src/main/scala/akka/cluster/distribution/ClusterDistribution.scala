/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (c) 2019 Borislav Borisov
 */

package akka.cluster.distribution

import akka.actor.DeadLetterSuppression
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.util.Timeout
import java.net.URLEncoder
import scala.concurrent.duration._
import scala.reflect.ClassTag

object ClusterDistribution extends ExtensionId[ClusterDistribution] {

  override def createExtension(system: ActorSystem[_]): ClusterDistribution = {
    new ClusterDistribution(system)
  }

}

/**
 * Distributes a static list of entities evenly over a cluster, ensuring that they are all continuously active.
 *
 * This uses cluster sharding underneath, using a scheduled tick sent to each entity as a mechanism to ensure they are
 * active.
 *
 * Entities are cluster sharding entities, so they can discover their ID by inspecting their name. Additionally,
 * entities should provide an "EnsureActive" message to ensure that they are active, typically they can do nothing in
 * response to it.
 */
final class ClusterDistribution(system: ActorSystem[_])(implicit val timeout: Timeout = 2.seconds) extends Extension {

  private val settings = ClusterShardingSettings(system)
  private val sharding = ClusterSharding(system)

  /**
   * Start a cluster distribution.
   *
   * @param typeName The name of the type of entity. This is used as the cluster sharding type name.
   * @param createBehavior The initial behavior of the entity.
   * @param ensureActiveMessage A command that will be sent to each entity in a cluster distribution to ensure it's active.
   * @param ensureActiveInterval The interval at which entities are ensured to be active.
   * @param entityIds The entity ids to distribute over the cluster.
   * @tparam Command The entity's message protocol.
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start[Command: ClassTag](
      typeName: String,
      createBehavior: EntityId => Behavior[Command],
      ensureActiveMessage: EntityId => Command,
      ensureActiveInterval: FiniteDuration,
      entityIds: Set[EntityId]
  ): ActorRef[ShardingEnvelope[Command]] = {
    system.log.debug(s"Starting cluster distribution for [$typeName].")

    // The ClusterSharding extension uses HashCodeMessageExtractor
    // to handle both the entityId and shardId extraction
    val typeKey = EntityTypeKey[Command](typeName)
    val entity = Entity(typeKey, entityContext => createBehavior(entityContext.entityId))
        .withMessageExtractor(new HashCodeMessageExtractor[Command](settings.numberOfShards))

    // The init() will decide whether to start this in proxy mode or not, depending on the Cluster settings
    val shardRegion: ActorRef[ShardingEnvelope[Command]] = sharding.init(entity)

    system.systemActorOf(
      DistributionWatchdog(typeName, entityIds, shardRegion, ensureActiveMessage, ensureActiveInterval),
      "cluster-distribution-watchdog-" + URLEncoder.encode(typeName, "utf-8")
    )

    shardRegion
  }

}

private[cluster] object DistributionWatchdog {
  private val WatchdogTick = "EnsureActiveTick"

  def apply[Command](
      typeName: String,
      entityIds: Set[EntityId],
      shardRegion: ActorRef[ShardingEnvelope[Command]],
      ensureActiveMessage: EntityId => Command,
      ensureActiveInterval: FiniteDuration
  ): Behavior[WatchdogCommand] = Behaviors.setup { context =>
    context.watch(shardRegion)

    Behaviors.withTimers { timers =>
        // XXX: should we start on init and then run the ticks? Or make this configurable?
      timers.startPeriodicTimer(WatchdogTick, Tick, ensureActiveInterval)
      Behaviors.receiveMessage[WatchdogCommand] {
        case Tick =>
          context.log.debug(s"ClusterDistributor [$typeName] sending ensure active messages to [$entityIds]")
          entityIds.foreach { entityId =>
            shardRegion ! ShardingEnvelope(s"$typeName-$entityId", ensureActiveMessage(entityId))
          }
          Behaviors.same
      }.receiveSignal {
        case (ctx, PostStop) =>
          ctx.log.debug(s"The cluster distribution for [$typeName] is stopped.")
          timers.cancel(WatchdogTick) // FIXME: this seems to be automatically stopped, might not be needed
          Behaviors.same
        case (ctx, Terminated(`shardRegion`)) =>
          ctx.log.debug(s"The shard region [$shardRegion] is terminated. Stopping the cluster distribution for [$typeName].")
          Behaviors.stopped
      }
    }
  }

  trait WatchdogCommand
  final case object Tick extends WatchdogCommand with DeadLetterSuppression

}
