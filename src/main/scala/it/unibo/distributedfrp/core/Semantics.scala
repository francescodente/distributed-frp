package it.unibo.distributedfrp.core

import it.unibo.distributedfrp.core._
import it.unibo.distributedfrp.core.Slot._
import it.unibo.distributedfrp.frp.FrpGivens.given
import it.unibo.distributedfrp.frp.FrpExtensions._
import it.unibo.distributedfrp.utils.Lift
import it.unibo.distributedfrp.utils.Lift._
import nz.sodium.time.SecondsTimerSystem
import nz.sodium.{Cell, CellLoop, Operational, Stream, Transaction}

trait Semantics extends Core, Language, CoreExtensions:
  override type Context <: BasicContext
  type NeighborInfo <: BasicNeighborInfo

  trait BasicNeighborInfo:
    def sensor[A](id: SensorId): A
    def exported: Export[Any]

  trait BasicContext:
    val timerSystem: SecondsTimerSystem = new SecondsTimerSystem
    def selfId: DeviceId
    def sensor[A](id: SensorId): Cell[A]
    def neighbors: Cell[NeighborField[NeighborInfo]]

  override def flowOf[A](f: Context ?=> Path => Cell[Export[A]]): Flow[A] = new Flow[A]:
    override def exports(path: Path)(using Context): Cell[Export[A]] = f(path)

  private def alignWithNeighbors[T](path: Path)(f: (Export[Any], NeighborInfo) => T)(using ctx: Context): Cell[NeighborField[T]] =
    ctx.neighbors.map(_.filterMap(field => field.exported.followPath(path).map(f(_, field))))

  override def mid: Flow[DeviceId] = Flows.constant(summon[Context].selfId)

  override def nbr[A](a: Flow[A]): Flow[NeighborField[A]] =
    flowOf { path =>
      val neighboringValues = alignWithNeighbors(path :+ Nbr)((e, _) => e.root.asInstanceOf[A])
      lift(a.exports(path :+ Nbr), neighboringValues){ (x, n) =>
        val neighborField = NeighborField(n.neighborValues + (summon[Context].selfId -> x.root))
        Export(neighborField, Nbr -> x)
      }
    }

  override def branch[A](cond: Flow[Boolean])(th: Flow[A])(el: Flow[A]): Flow[A] =
    flowOf { path =>
      val condExport = cond.exports(path :+ BranchCondition)
      val thenExport = th.exports(path :+ BranchSide(true))
      val elseExport = el.exports(path :+ BranchSide(false))
      condExport.lift(thenExport, elseExport, (c, t, e) => {
        val selected = if c.root then t else e
        Export(selected.root, BranchCondition -> c, BranchSide(c.root) -> selected)
      })
    }

  override def loop[A](init: A)(f: Flow[A] => Flow[A]): Flow[A] =
    flowOf { path =>
      Transaction.run(() => {
        val output = new CellLoop[Export[A]]()
        val processedOutput = Operational.defer(Operational.value(output).calm)
          .throttle(summon[Context].timerSystem, 0.1)
          .hold(Export(init))
        output.loop(f(flowOf(_ => processedOutput.map(x => Export(x.root)))).exports(path))
        output
      })
    }

  override def nbrSensor[A](id: SensorId): Flow[NeighborField[A]] =
    flowOf { path =>
      val alignedNeighbors = alignWithNeighbors(path)((_, n) => n.sensor[A](id))
      alignedNeighbors.map(Export(_))
    }

  override def sensor[A](id: SensorId): Flow[A] =
    Flows.fromCell(summon[Context].sensor[A](id))
