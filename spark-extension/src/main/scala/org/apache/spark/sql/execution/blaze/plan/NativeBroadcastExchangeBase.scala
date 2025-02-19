/*
 * Copyright 2022 The Blaze Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.blaze.plan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.concurrent.Promise

import org.apache.spark.OneToOneDependency
import org.apache.spark.Partition
import org.apache.spark.SparkException
import org.apache.spark.TaskContext
import org.apache.spark.broadcast
import org.blaze.{protobuf => pb}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.blaze.BlazeConf
import org.apache.spark.sql.blaze.JniBridge
import org.apache.spark.sql.blaze.MetricNode
import org.apache.spark.sql.blaze.NativeConverters
import org.apache.spark.sql.blaze.NativeHelper
import org.apache.spark.sql.blaze.NativeRDD
import org.apache.spark.sql.blaze.NativeSupports
import org.apache.spark.sql.blaze.Shims
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.plans.physical.BroadcastMode
import org.apache.spark.sql.catalyst.plans.physical.BroadcastPartitioning
import org.apache.spark.sql.catalyst.plans.physical.IdentityBroadcastMode
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.blaze.plan.NativeBroadcastExchangeBase.buildBroadcastData
import org.apache.spark.sql.execution.exchange.BroadcastExchangeExec
import org.apache.spark.sql.execution.exchange.BroadcastExchangeLike
import org.apache.spark.sql.execution.joins.HashedRelationBroadcastMode
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.metric.SQLMetrics

abstract class NativeBroadcastExchangeBase(mode: BroadcastMode, override val child: SparkPlan)
    extends BroadcastExchangeLike
    with NativeSupports {

  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = BroadcastPartitioning(mode)

  protected lazy val isNative: Boolean = {
    getTagValue(NativeBroadcastExchangeBase.nativeExecutionTag).getOrElse(false)
  }
  protected val nativeSchema: pb.Schema = Util.getNativeSchema(output)

  def getRunId: UUID

  override lazy val metrics: Map[String, SQLMetric] = Map(
    NativeHelper
      .getDefaultNativeMetrics(sparkContext)
      .toSeq :+
      ("dataSize", SQLMetrics.createSizeMetric(sparkContext, "data size")) :+
      ("numOutputRows", SQLMetrics.createMetric(sparkContext, "number of output rows")) :+
      ("collectTime", SQLMetrics.createTimingMetric(sparkContext, "time to collect")) :+
      ("buildTime", SQLMetrics.createTimingMetric(sparkContext, "time to build")) :+
      ("broadcastTime", SQLMetrics.createTimingMetric(sparkContext, "time to broadcast")): _*)

  override protected def doExecute(): RDD[InternalRow] = {
    throw new UnsupportedOperationException(
      "BroadcastExchange does not support the execute() code path.")
  }

  override def doPrepare(): Unit = {
    // Materialize the future.
    relationFuture
  }

  override def doExecuteBroadcast[T](): Broadcast[T] = {
    val broadcast = nonNativeBroadcastExec.executeBroadcast[T]()
    for ((k, metric) <- nonNativeBroadcastExec.metrics) {
      metrics.get(k).foreach(_.add(metric.value))
    }
    broadcast
  }

  def doExecuteBroadcastNative[T](): broadcast.Broadcast[T] = {
    val conf = SparkSession.getActiveSession.map(_.sqlContext.conf).orNull
    val timeout: Long = conf.broadcastTimeout
    try {
      nativeRelationFuture.get(timeout, TimeUnit.SECONDS).asInstanceOf[broadcast.Broadcast[T]]
    } catch {
      case ex: TimeoutException =>
        logError(s"Could not execute broadcast in $timeout secs.", ex)
        if (!nativeRelationFuture.isDone) {
          sparkContext.cancelJobGroup(getRunId.toString)
          nativeRelationFuture.cancel(true)
        }
        throw new SparkException("Native broadcast exchange timed out.", ex)
    }
  }

  override def doExecuteNative(): NativeRDD = {
    val broadcast = doExecuteBroadcastNative[Array[Array[Byte]]]()
    val nativeMetrics = MetricNode(metrics, Nil)
    val partitions = Array(new Partition() {
      override def index: Int = 0
    })

    new NativeRDD(
      sparkContext,
      nativeMetrics,
      partitions,
      Nil,
      rddShuffleReadFull = true,
      (_, _) => {
        val resourceId = s"ArrowBroadcastExchangeExec:${UUID.randomUUID()}"
        val provideIpcIterator = () => {
          broadcast.value.iterator.map(bytes => {
            Channels.newChannel(new ByteArrayInputStream(bytes))
          })
        }
        JniBridge.resourcesMap.put(resourceId, () => provideIpcIterator())
        pb.PhysicalPlanNode
          .newBuilder()
          .setIpcReader(
            pb.IpcReaderExecNode
              .newBuilder()
              .setSchema(nativeSchema)
              .setNumPartitions(1)
              .setIpcProviderResourceId(resourceId)
              .setMode(pb.IpcReadMode.CHANNEL)
              .build())
          .build()
      },
      friendlyName = "NativeRDD.BroadcastRead")
  }

  def collectNative(): Array[Array[Byte]] = {
    val inputRDD = NativeHelper.executeNative(child match {
      case child if NativeHelper.isNative(child) => child
      case child => Shims.get.createConvertToNativeExec(child)
    })
    val modifiedMetrics = metrics ++ Map("output_rows" -> metrics("numOutputRows"))
    val nativeMetrics = MetricNode(modifiedMetrics, inputRDD.metrics :: Nil)

    val ipcRDD =
      new RDD[Array[Byte]](sparkContext, new OneToOneDependency(inputRDD) :: Nil) {
        setName("NativeRDD.BroadcastWrite")
        Shims.get.setRDDShuffleReadFull(this, inputRDD.isShuffleReadFull)

        override protected def getPartitions: Array[Partition] = inputRDD.partitions

        override def compute(split: Partition, context: TaskContext): Iterator[Array[Byte]] = {
          val resourceId = s"ArrowBroadcastExchangeExec.input:${UUID.randomUUID()}"
          val ipcs = ArrayBuffer[Array[Byte]]()
          JniBridge.resourcesMap.put(
            resourceId,
            (byteBuffer: ByteBuffer) => {
              val byteArray = new Array[Byte](byteBuffer.capacity())
              byteBuffer.get(byteArray)
              ipcs += byteArray
              metrics("dataSize") += byteArray.length
            })

          val input = inputRDD.nativePlan(inputRDD.partitions(split.index), context)
          val nativeIpcWriterExec = pb.PhysicalPlanNode
            .newBuilder()
            .setIpcWriter(
              pb.IpcWriterExecNode
                .newBuilder()
                .setInput(input)
                .setIpcConsumerResourceId(resourceId)
                .build())
            .build()

          // execute ipc writer and fill output channels
          val iter =
            NativeHelper.executeNativePlan(
              nativeIpcWriterExec,
              nativeMetrics,
              split,
              Some(context))
          assert(iter.isEmpty)

          // return ipcs as iterator
          ipcs.iterator
        }
      }

    // build broadcast data
    val collectedData = ipcRDD.collect()
    val keys = mode match {
      case mode: HashedRelationBroadcastMode => mode.key
      case IdentityBroadcastMode => Nil
    }
    buildBroadcastData(collectedData, keys, nativeSchema)
  }

  @transient
  lazy val relationFuture: Future[Broadcast[Any]] = if (isNative) {
    nativeRelationFuture.asInstanceOf[Future[Broadcast[Any]]]
  } else {
    val relationFutureField = classOf[BroadcastExchangeExec].getDeclaredField("relationFuture")
    relationFutureField.setAccessible(true)
    relationFutureField.get(nonNativeBroadcastExec).asInstanceOf[Future[Broadcast[Any]]]
  }

  @transient
  lazy val nonNativeBroadcastExec: BroadcastExchangeExec = {
    BroadcastExchangeExec(mode, child)
  }

  @transient
  lazy val nativeRelationFuture: Future[Broadcast[Array[Array[Byte]]]] = {
    SQLExecution.withThreadLocalCaptured[Broadcast[Array[Array[Byte]]]](
      Shims.get.getSqlContext(this).sparkSession,
      BroadcastExchangeExec.executionContext) {
      try {
        sparkContext.setJobGroup(
          getRunId.toString,
          s"native broadcast exchange (runId $getRunId)",
          interruptOnCancel = true)
        val broadcasted = sparkContext.broadcast(collectNative())
        Promise[Broadcast[Array[Array[Byte]]]].trySuccess(broadcasted)
        broadcasted
      } catch {
        case e: Throwable =>
          Promise[Broadcast[Array[Array[Byte]]]].tryFailure(e)
          throw e
      }
    }
  }

  override protected def doCanonicalize(): SparkPlan =
    Shims.get.createNativeBroadcastExchangeExec(mode.canonicalized, child.canonicalized)
}

object NativeBroadcastExchangeBase {

  def nativeExecutionTag: TreeNodeTag[Boolean] = TreeNodeTag("arrowBroadcastNativeExecution")

  def buildBroadcastData(
      collectedData: Array[Array[Byte]],
      keys: Seq[Expression],
      nativeSchema: pb.Schema): Array[Array[Byte]] = {

    if (!BlazeConf.BHJ_FALLBACKS_TO_SMJ_ENABLE.booleanConf() || keys.isEmpty) {
      return collectedData // no need to sort data in driver side
    }

    val readerIpcProviderResourceId = s"BuildBroadcastDataReader:${UUID.randomUUID()}"
    val readerExec = pb.IpcReaderExecNode
      .newBuilder()
      .setSchema(nativeSchema)
      .setIpcProviderResourceId(readerIpcProviderResourceId)
      .setMode(pb.IpcReadMode.CHANNEL)

    val sortExec = pb.SortExecNode
      .newBuilder()
      .setInput(pb.PhysicalPlanNode.newBuilder().setIpcReader(readerExec))
      .addAllExpr(
        keys
          .map(key => {
            pb.PhysicalExprNode
              .newBuilder()
              .setSort(
                pb.PhysicalSortExprNode
                  .newBuilder()
                  .setExpr(NativeConverters.convertExpr(key))
                  .setAsc(true)
                  .setNullsFirst(true)
                  .build())
              .build()
          })
          .asJava)

    val writerIpcProviderResourceId = s"BuildBroadcastDataWriter:${UUID.randomUUID()}"
    val writerExec = pb.IpcWriterExecNode
      .newBuilder()
      .setInput(pb.PhysicalPlanNode.newBuilder().setSort(sortExec))
      .setIpcConsumerResourceId(writerIpcProviderResourceId)

    // build native sorter
    val exec = pb.PhysicalPlanNode
      .newBuilder()
      .setIpcWriter(writerExec)
      .build()

    // input
    val provideIpcIterator = () => {
      collectedData.iterator.map { ipc =>
        val inputStream = new ByteArrayInputStream(ipc)
        Channels.newChannel(inputStream)
      }
    }
    JniBridge.resourcesMap.put(readerIpcProviderResourceId, () => provideIpcIterator())

    // output
    val bos = new ByteArrayOutputStream()
    val consumeIpc = (byteBuffer: ByteBuffer) => {
      val byteArray = new Array[Byte](byteBuffer.capacity())
      byteBuffer.get(byteArray)
      bos.write(byteArray)
    }
    JniBridge.resourcesMap.put(writerIpcProviderResourceId, consumeIpc)

    // execute
    val singlePartition = new Partition {
      override def index: Int = 0
    }
    assert(NativeHelper.executeNativePlan(exec, null, singlePartition, None).isEmpty)
    Array(bos.toByteArray)
  }
}
