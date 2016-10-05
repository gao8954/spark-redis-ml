package com.redislabs.provider.redis.ml

import org.apache.spark.ml.tree.{InternalNode, ContinuousSplit, CategoricalSplit}
import org.apache.spark.ml.classification.DecisionTreeClassificationModel

import redis.clients.jedis.Protocol.Command
import redis.clients.jedis.{Jedis, _}


class Forest(trees: Array[DecisionTreeClassificationModel]) {

  private def subtreeToRedisString(n: org.apache.spark.ml.tree.Node, path: String = "."): String = {
    val prefix: String = s",${path},"
    n.getClass.getSimpleName match {
      case "InternalNode" => {
        val in = n.asInstanceOf[InternalNode]
        val splitStr = in.split match {
          case contSplit: ContinuousSplit => s"numeric,${in.split.featureIndex},${contSplit.threshold}"
          case catSplit: CategoricalSplit => s"categoric,${in.split.featureIndex}," + catSplit.leftCategories.mkString(":")
        }
        prefix + splitStr + subtreeToRedisString(in.leftChild, path + "l") +
          subtreeToRedisString(in.rightChild, path + "r")
      }
      case "LeafNode" => {
        prefix + s"leaf,${n.prediction}"
      }
    }
  }

  private def toRedisString: String = {
    trees.zipWithIndex.map { case (tree, treeIndex) =>
      s"${treeIndex}" + subtreeToRedisString(tree.rootNode, ".")
    }.fold("") { (a, b) => a + "\n" + b }
  }


  def loadToRedis(forestId: String = "test_forest", host: String = "localhost") {
    val jedis = new Jedis(host)
    val commands = toRedisString.split("\n").drop(1)
    jedis.getClient.sendCommand(Command.MULTI)
    jedis.getClient().getStatusCodeReply
    for (cmd <- commands) {
      val cmdArray = forestId +: cmd.split(",")
      jedis.getClient.sendCommand(Command.FOREST_ADD, cmdArray: _*)
      jedis.getClient().getStatusCodeReply
    }
    jedis.getClient.sendCommand(Command.EXEC)
    jedis.getClient.getMultiBulkReply
  }
}