/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.distribution

import akka.cluster.sharding.typed.{ShardingEnvelope, ShardingMessageExtractor}

object HashCodeMessageExtractor {

  // In the implementation shipped with Akka we can observe the following remark:
  // "It would be better to have abs(id.hashCode % maxNumberOfShards), see issue #25034
  // but to avoid getting different values when rolling upgrade we keep the old way,
  // and it doesn't have any serious consequences"
  //
  // We are not bound by such limitations, so we've fixed the implementation.
  private[cluster] def shardId(id: String, maxNumberOfShards: Int): String = {
    math.abs(id.hashCode % maxNumberOfShards).toString
  }
}

/**
 * Default message extractor type, using envelopes to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam M The type of message accepted by the entity actor
 */
final class HashCodeMessageExtractor[M](val numberOfShards: Int)
    extends ShardingMessageExtractor[ShardingEnvelope[M], M] {

  override def entityId(envelope: ShardingEnvelope[M]): String = envelope.entityId

  override def shardId(entityId: String): String = HashCodeMessageExtractor.shardId(entityId, numberOfShards)

  override def unwrapMessage(envelope: ShardingEnvelope[M]): M = envelope.message

}
