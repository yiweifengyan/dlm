package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

// Two nodes + RDMA flow
class TwoNodeNetTop(implicit sysConf: SysConfig) extends Component {
  val io = Array.fill(2)(new NodeNetIO())
  val n = Array.fill(2)(new WrapNodeNet())
  (n, io).zipped.foreach(_.io.connectAllByName(_))
}

object WrapNodeNetSim {

  def main(args: Array[String]): Unit = {

    implicit val sysConf = new SysConfig {
      override val nNode: Int = 2
      override val nCh: Int = 1
      override val nTxnMan: Int = 2
      override val nLtPart: Int = 1
      override val nLock: Int = (((1<<10)<<10)<<8)>>6
      override val wTimeOut: Int = 10
    }

    SimConfig
      .withWave
      .compile {
      val dut = new TwoNodeNetTop()
      dut.n.foreach { m =>
        m.io.simPublic()
      }
      dut
    }.doSim("wrapnodesim", 99) { dut =>

      dut.clockDomain.forkStimulus(period = 10)

      // params
      val txnLen = 8
      val txnCnt = 8
      val txnMaxLen = sysConf.maxTxnLen - 1

      for (idx <- 0 until sysConf.nNode) {
        for (iTxnMan <- 0 until sysConf.nTxnMan) {
          // cmd memory
          val fNId = (i: Int, j: Int) => (i * txnLen + j) % sysConf.nNode
          val fCId = (i: Int, j: Int) => (i * txnLen + j) % sysConf.nCh
          // for different txnMan, there'll be a tIdOffs in txnEntrySimInt
          val fTId = (i: Int, j: Int) => i * txnLen + j
          val fLkAttr = (i: Int, j: Int) => 0
          val fWLen = (i: Int, j: Int) => 0
          val txnCtx = SimInit.txnEntrySimInt(txnCnt, txnLen, txnMaxLen, 0)(fNId, fCId, fTId, fLkAttr, fWLen).toArray
          SimDriver.instAxiMemSim(dut.io(idx).node.cmdAxi(iTxnMan), dut.clockDomain, Some(txnCtx))
          // data memory
          SimDriver.instAxiMemSim(dut.io(idx).node.axi(iTxnMan), dut.clockDomain, None)
          println(s"Axi Memory initialized for node ${idx} Txn Manager ${iTxnMan}")
        }
        for (iTxnAgent <- sysConf.nTxnMan until sysConf.nTxnMan + sysConf.nNode - 1) {
          // data memory
          SimDriver.instAxiMemSim(dut.io(idx).node.axi(iTxnAgent), dut.clockDomain, None)
        }
      }
      println(s"All Axi Memory initialized.")
      // connect rdma sim switch
      SimDriver.rdmaSwitch(dut.clockDomain, 2, 1000, dut.io.map(_.rdma.sq), dut.io.map(_.rdma.rd_req),
        dut.io.map(_.rdma.wr_req), dut.io.map(_.rdma.axis_src), dut.io.map(_.rdma.axis_sink), dut.io.map(_.rdma.ack))
      println(s"RDMA initialized.")
      // node & rdma ctrl
      dut.io.zipWithIndex.foreach { case (e, idx) =>
        e.node.nodeId #= idx
        e.node.start #= false
        e.node.txnNumTotal #= txnCnt
        e.node.cmdAddrOffs.foreach(_ #= 0) // now each txnMan has individual cmdMem ch
        e.rdmaCtrl.foreach(_.en #= false)
        e.rdmaCtrl.foreach(_.len #= 1024)
        e.rdmaCtrl.foreach(_.qpn #= 0)
        e.rdmaCtrl(0).flowId #= 1 // for each node, mstr flowId is 1, wr to rmt slve
        e.rdmaCtrl(1).flowId #= 0 // mstr flowId is 0, wr to rmt mstr
      }
      println(s"Node config finished.")
      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling((1<<sysConf.wHtTable) + 1000)

      // start
      dut.io.foreach { e =>
        e.node.start #= true
        e.rdmaCtrl.foreach(_.en #= true)
        dut.clockDomain.waitSampling()
        e.node.start #= false
      }
      println(s"Nodes started.")

      dut.io.foreach(_.node.done.foreach(a => dut.clockDomain.waitSamplingWhere(a.toBoolean)))
      dut.io.zipWithIndex.foreach { case (e, idx) =>
        Seq(e.node.cntTxnLd, e.node.cntTxnCmt, e.node.cntTxnAbt, e.node.cntClk, e.node.cntLockLoc, e.node.cntLockRmt, e.node.cntLockDenyLoc, e.node.cntLockDenyRmt).foreach { sigV =>
          sigV.foreach { sig =>
            println(s"Node[$idx]  ${sig.getName()} = ${sig.toBigInt}")
          }
        }
      }

    }
  }

}