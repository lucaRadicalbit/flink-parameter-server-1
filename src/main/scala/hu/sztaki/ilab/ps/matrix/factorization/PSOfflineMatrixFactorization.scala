package hu.sztaki.ilab.ps.matrix.factorization

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.{Condition, ReentrantLock}

import hu.sztaki.ilab.ps.client.receiver.SimpleClientReceiver
import hu.sztaki.ilab.ps.client.sender.SimpleClientSender
import hu.sztaki.ilab.ps.entities.{WorkerIn, WorkerOut}
import hu.sztaki.ilab.ps.matrix.factorization.Utils._
import hu.sztaki.ilab.ps.server.SimplePSLogic
import hu.sztaki.ilab.ps.server.receiver.SimplePSReceiver
import hu.sztaki.ilab.ps.server.sender.SimplePSSender
import hu.sztaki.ilab.ps.{FlinkPS, ParameterServerClient, WorkerLogic}
import org.apache.flink.api.common.functions.{Partitioner, RichFlatMapFunction}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class PSOfflineMatrixFactorization {
}

/**
  * A use-case for PS: matrix factorization with SGD.
  * Reads data into workers, then iterates on it, storing the user vectors at the worker and item vectors at the PS.
  * At every iteration, the worker pulls the relevant item vectors, applies the SGD updates and pushes the updates.
  *
  */
object PSOfflineMatrixFactorization {

  private val log = LoggerFactory.getLogger(classOf[PSOfflineMatrixFactorization])

  // A user rates an item with a Double rating
  type Rating = (UserId, ItemId, Double)
  type Vector = Array[Double]

  def psOfflineMF(src: DataStream[Rating],
                  numFactors: Int,
                  learningRate: Double,
                  iterations: Int,
                  pullLimit: Long,
                  workerParallelism: Int,
                  psParallelism: Int,
                  iterationWaitTime: Long): DataStream[Either[(UserId, Vector), (ItemId, Vector)]] = {

    val readParallelism = src.parallelism

    val ratings = src
      .flatMap(new RichFlatMapFunction[Rating, Rating] {

        var collector: Option[Collector[Rating]] = None

        override def flatMap(rating: Rating, out: Collector[Rating]): Unit = {
          collector = Some(out)
          out.collect(rating)
        }

        override def close(): Unit = {
          collector match {
            case Some(c: Collector[Rating]) =>
              for (i <- 0 until workerParallelism)
                c.collect((i, -getRuntimeContext.getIndexOfThisSubtask, -1.0))
            case _ => log.error("Nothing to collect from the source, quite tragic.")
          }
        }
      }).setParallelism(readParallelism)
      .partitionCustom(new Partitioner[UserId] {
        override def partition(key: UserId, numPartitions: Int): Int = key % numPartitions
      }, x => x._1)

    // initialization method and update method
    val factorInitDesc = RandomFactorInitializerDescriptor(numFactors)

    // fixme add lambda
    val factorUpdate = new SGDUpdater(learningRate)

    val workerLogic = new WorkerLogic[Rating, Vector, (UserId, Vector)] {

      val userVectors = new mutable.HashMap[UserId, Vector]()
      // we store ratings by items, so that we can apply updates for all relevant users at a pull answer
      val itemRatings = new mutable.HashMap[ItemId, ArrayBuffer[(UserId, Double)]]()

      @transient
      lazy val factorInit: FactorInitializer = factorInitDesc.open()

      // todo gracefully stop thread at end of computation (override close method)
      var workerThread: Thread = null

      // We need to check if all threads finished already
      val EOFsReceived = new AtomicInteger(0)

      var pullCounter = 0
      val psLock = new ReentrantLock()
      val canPull: Condition = psLock.newCondition()

      override def onRecv(data: Rating,
                          ps: ParameterServerClient[Vector, (UserId, Vector)]): Unit = {

        data match {
          case (workerId, minusSourceId, -1.0) =>
            log.info(s"Received EOF @$workerId from ${-minusSourceId}")
            EOFsReceived.getAndIncrement()
            // Start working when all the threads finished reading.
            if (EOFsReceived.get() >= readParallelism) {
              // This marks the end of input. We can do the work.
              log.info(s"Number of received ratings: ${itemRatings.values.map(_.length).sum}")

              // We start a new Thread to avoid blocking the answers to pulls.
              // Otherwise the work would only start when all the pulls are sent (for all iterations).
              workerThread = new Thread(new Runnable {
                override def run(): Unit = {
                  log.debug("worker thread started")
                  for (iter <- 1 to iterations) {
                    for (item <- itemRatings.keys) {
                      // we assume that the PS client is not thread safe, so we need to sync when we use it.
                      psLock.lock()
                      try {
                        while (pullCounter >= pullLimit) {
                          canPull.await()
                        }
                        pullCounter += 1
                        log.debug(s"pull inc: $pullCounter")

                        ps.pull(item)
                      } finally {
                        psLock.unlock()
                      }
                    }
                  }
                  log.debug("pulls finished")
                }
              })
              workerThread.start()
            }
          case rating
            @(u, i, r) =>
            // Since the EOF signals likely won't arrive at the same time, an extra check for the finished sources is needed.
            // todo maybe use assert instead?
            if (workerThread != null) {
              throw new IllegalStateException("Should not have started worker thread while waiting for further " +
                "elements.")
            }

            val buffer = itemRatings.getOrElseUpdate(i, new ArrayBuffer[(UserId, Double)]())
            buffer.append((u, r))
        }
      }

      override def onPullRecv(item: ItemId,
                              itemVec: Vector,
                              ps: ParameterServerClient[Vector, (UserId, Vector)]): Unit = {
        // we assume that the PS client is not thread safe, so we need to sync when we use it.
        psLock.lock()
        try {
          // todo shuffle or not?
          for ((user, rating) <- Random.shuffle(itemRatings(item))) {
            val userVec = userVectors.getOrElseUpdate(user, factorInit.nextFactor(user))
            val (deltaUserVec, deltaItemVec) = factorUpdate.delta(rating, userVec, itemVec)
            userVectors(user) = userVec.zip(deltaUserVec).map(x => x._1 + x._2)
            ps.push(item, deltaItemVec)
          }
          pullCounter -= 1
          canPull.signal()
          log.debug(s"pull dec: $pullCounter")
        } finally {
          psLock.unlock()
        }
      }

      override def close(): Unit = {
        userVectors.foreach {
          case (id: Int, vector: Array[Double]) =>
            log.info(s"###PS###u;${id};[${vector.mkString(",")}]")
        }
      }
    }

    val workerLogic2 = new WorkerLogic[Rating, Vector, (UserId, Vector)] {

      val rs = new ArrayBuffer[Rating]()
      val userVectors = new mutable.HashMap[UserId, Vector]()
      val itemRatings = new mutable.HashMap[ItemId, mutable.Queue[(UserId, Double)]]()

      @transient
      lazy val factorInit: FactorInitializer = factorInitDesc.open()

      // todo gracefully stop thread at end of computation (override close method)
      var workerThread: Thread = null

      // We need to check if all threads finished already
      //        val EOFsReceived = new AtomicInteger(0)
      var EOFsReceived = 0

      var pullCounter = 0
      val psLock = new ReentrantLock()
      val canPull: Condition = psLock.newCondition()

      override def onRecv(data: Rating,
                          ps: ParameterServerClient[Vector, (UserId, Vector)]): Unit = {

        data match {
          case (workerId, minusSourceId, -1.0) =>
            log.info(s"Received EOF @$workerId from ${-minusSourceId}")
            EOFsReceived += 1
            // Start working when all the threads finished reading.
            if (EOFsReceived >= readParallelism) {
              // This marks the end of input. We can do the work.
              log.info(s"Number of received ratings: ${rs.length}")

              // We start a new Thread to avoid blocking the answers to pulls.
              // Otherwise the work would only start when all the pulls are sent (for all iterations).
              workerThread = new Thread(new Runnable {
                override def run(): Unit = {
                  log.debug("worker thread started")
                  for (iter <- 1 to iterations) {
                    Random.shuffle(rs)
                    for ((u, i, r) <- rs) {
                      // we assume that the PS client is not thread safe, so we need to sync when we use it.
                      psLock.lock()
                      try {
                        while (pullCounter >= pullLimit) {
                          canPull.await()
                        }
                        pullCounter += 1
                        log.debug(s"pull inc: $pullCounter")

                        itemRatings.getOrElseUpdate(i, mutable.Queue[(UserId, Double)]())
                          .enqueue((u, r))

                        ps.pull(i)
                      } finally {
                        psLock.unlock()
                      }

                    }
                  }
                  log.debug("pulls finished")
                }
              })
              workerThread.start()
            }
          case rating
            @(u, i, r) =>
            // Since the EOF signals likely won't arrive at the same time, an extra check for the finished sources is needed.
            // todo maybe use assert instead?
            if (workerThread != null) {
              throw new IllegalStateException("Should not have started worker thread while waiting for further " +
                "elements.")
            }

            rs.append((u, i, r))
        }
      }

      override def onPullRecv(item: ItemId,
                              itemVec: Vector,
                              ps: ParameterServerClient[Vector, (UserId, Vector)]): Unit = {
        // we assume that the PS client is not thread safe, so we need to sync when we use it.
        psLock.lock()
        try {
          // todo shuffle or not?
          val (user, rating) = itemRatings(item).dequeue()
          val userVec = userVectors.getOrElseUpdate(user, factorInit.nextFactor(user))
          val (deltaUserVec, deltaItemVec) = factorUpdate.delta(rating, userVec, itemVec)
          userVectors(user) = userVec.zip(deltaUserVec).map(x => x._1 + x._2)
          ps.output((user, userVectors(user)))
          ps.push(item, deltaItemVec)

          pullCounter -= 1
          canPull.signal()
          log.debug(s"pull dec: $pullCounter")
        } finally {
          psLock.unlock()
        }
      }

      override def close(): Unit = {
      }
    }
    val serverLogic =
      new SimplePSLogic[Array[Double]](
        x => factorInitDesc.open().nextFactor(x), { case (vec, deltaVec) => vec.zip(deltaVec).map(x => x._1 + x._2) })

    val paramPartitioner: WorkerOut[Array[Double]] => Int = {
      case WorkerOut(partitionId, msg) => msg match {
        case Left(paramId) => Math.abs(paramId) % psParallelism
        case Right((paramId, delta)) => Math.abs(paramId) % psParallelism
      }
    }

    val wInPartition: WorkerIn[Array[Double]] => Int = {
      case WorkerIn(id, workerPartitionIndex, msg) => workerPartitionIndex
    }

    val modelUpdates = FlinkPS.psTransform(ratings, workerLogic2, serverLogic,
      new SimpleClientReceiver[Array[Double]](),
      new SimpleClientSender[Array[Double]](),
      new SimplePSReceiver[Array[Double]](),
      new SimplePSSender[Array[Double]](),
      paramPartitioner = paramPartitioner,
      wInPartition = wInPartition,
      workerParallelism,
      psParallelism,
      iterationWaitTime)

    modelUpdates
  }
}