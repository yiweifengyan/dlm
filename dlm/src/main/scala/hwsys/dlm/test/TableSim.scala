package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._

class OneTxnManOneLockTable(sysConf: MinSysConfig) extends Component {

  val txnMan = new TxnManAgent(sysConf)
  val table = new LockTableBWait(sysConf)

  val io = new Bundle {
    val dataAXI = master(Axi4(sysConf.axiConf))
    val loadAXI = master(Axi4(sysConf.axiConf))
    // txnMan config
    val txnNumTotal = in UInt (32 bits) // total txns to load
    val loadAddrBase = in UInt (32 bits) // loading address base. NOTE: unit size 512B
    // control signals (wire the input to the top AXIL registers)
    val start = in Bool() //NOTE: hold for 1 cycle
    val done = out(Reg(Bool())).init(False)
    val cntTxnCmt, cntTxnAbt, cntTxnLd, cntLockLoc, cntLockRmt, cntLockDenyLoc, cntLockDenyRmt = out(Reg(UInt(32 bits))).init(0) // Local lock analysis
    val cntRmtLockGrant, cntRmtLockWait, cntRmtLockDeny, cntRmtLockRelease = out(Reg(UInt(32 bits))).init(0) // Lock received from remote nodes
    val cntClk = out(Reg(UInt(sysConf.wTimeStamp bits))).init(0)
  }
  txnMan.io.nodeIdx := 0
  txnMan.io.txnManIdx := 0
  txnMan.io.connectSomeByName(io)

  table.io.channelIdx := 0
  table.io.start := io.start
  txnMan.io.localLockReq >> table.io.lockRequest
  txnMan.io.localLockResp << table.io.lockResponse

  txnMan.io.toRemoteLockReq.ready := False
  txnMan.io.toRemoteLockResp.ready := False
  txnMan.io.toRemoteRead.ready := False 
  txnMan.io.toRemoteWrite.ready := False 
  txnMan.io.fromRemoteLockReq.valid := False
  txnMan.io.fromRemoteLockReq.payload.assignFromBits(B(0, 48 bits))
  txnMan.io.fromRemoteLockResp.valid := False
  txnMan.io.fromRemoteLockResp.payload.assignFromBits(B(0, 48 bits))
  txnMan.io.fromRemoteRead.valid := False
  txnMan.io.fromRemoteRead.payload.assignFromBits(B(0, 512 bits))
  txnMan.io.fromRemoteWrite.valid := False
  txnMan.io.fromRemoteWrite.payload.assignFromBits(B(0, 512 bits))
}

object TableSim{
  def main(args: Array[String]): Unit = {

    implicit val sysConf = new MinSysConfig {
      override val nNode: Int = 2
      override val nChannel: Int = 2
      override val nTable: Int = 1
      override val nLock: Int = 65536
      override val nTxnMan: Int = 1
    }

    SimConfig.withWave.compile {
      // val dut = new OneTxnManOneLockTable(sysConf)
      val dut = new OneTxnManOneLockTable(sysConf)
      dut.txnMan.io.simPublic()
      dut
    }.doSim("OneTxnManOneLockTable", 99) { dut =>
      // params
      val txnLen = 30
      val txnCnt = 1024
      val txnMaxLen = sysConf.maxTxnLen

      dut.clockDomain.forkStimulus(period = 10)

      // cmd memory and data memory
      val fNId = (i: Int, j: Int) => 0
      val fCId = (i: Int, j: Int) => 0
      val fTId = (i: Int, j: Int) => 0 // Table ID
      val fLockID = (i: Int, j: Int) => 16 + j  // 1024 txns ask for one shared lock
      val fLockType = (i: Int, j: Int) => 1 // Read
      val fWLen   = (i: Int, j: Int) => 1
      val txnCtx  = SimInit.txnEntrySim(txnCnt, txnLen, txnMaxLen)(fNId, fCId, fTId, fLockID, fLockType, fWLen).toArray
      val loadAxiMem = SimDriver.instAxiMemSim(dut.io.loadAXI, dut.clockDomain, Some(txnCtx))
      val dataAxiMem = SimDriver.instAxiMemSim(dut.io.dataAXI, dut.clockDomain, None)

      dut.io.start #= false
      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(sysConf.nLock / sysConf.nTable + 1000)

      // config
      dut.io.loadAddrBase #= 0
      dut.io.txnNumTotal #= txnCnt

      // start
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      // dut.clockDomain.waitSampling(64000)

      dut.clockDomain.waitSamplingWhere(dut.txnMan.io.done.toBoolean)

      println(s"[txnMan] cntTxnCmt: ${dut.txnMan.io.cntTxnCmt.toBigInt}")
      println(s"[txnMan] cntTxnAbt: ${dut.txnMan.io.cntTxnAbt.toBigInt}")
      println(s"[txnMan] cntTxnLd: ${dut.txnMan.io.cntTxnLd.toBigInt}")
      println(s"[txnMan] cntClk: ${dut.txnMan.io.cntClk.toBigInt}")
    }
  }
}
