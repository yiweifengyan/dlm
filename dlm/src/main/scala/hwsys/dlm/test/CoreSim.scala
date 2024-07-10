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
//  io.dataAXI <> txnMan.io.dataAXI
//  io.loadAXI <> txnMan.io.loadAXI
//  io.start <> txnMan.io.start
//  io.done <> txnMan.io.done
//  io.txnNumTotal <> txnMan.io.txnNumTotal
//  io.loadAddrBase <> txnMan.io.loadAddrBase
//  io.cntTxnCmt <> txnMan.io.cntTxnCmt
//  io.cntTxnAbt <> txnMan.io.cntTxmAbt
//  io.cntTxnLd <> txnMan.io.cntTxnLd
//  io.cntLockLoc <> txnMan.io.cntLockLoc
//  io.cntLockRmt <> txnMan.io.cntLockRmt
//  io.cntLockDenyLoc<> txnMan.io.cntLockDenyLoc
//  io.cntLockDenyRmt <> txnMan.io.cntLockDenyRmt

  table.io.channelIdx := 0
  table.io.start := io.start
  txnMan.io.localLockReq >> table.io.lockRequest
  txnMan.io.localLockResp << table.io.lockResponse

  txnMan.io.toRemoteLockReq >> txnMan.io.fromRemoteLockReq
  txnMan.io.toRemoteLockResp >> txnMan.io.fromRemoteLockResp
  txnMan.io.toRemoteRead >> txnMan.io.fromRemoteRead
  txnMan.io.toRemoteWrite >> txnMan.io.fromRemoteWrite
}

object CoreSim{
  def main(args: Array[String]): Unit = {

    implicit val sysConf = new MinSysConfig {
      override val nNode: Int = 1
      override val nChannel: Int =1
      override val nTable: Int = 1
      override val nLock: Int = 65536
      override val nTxnMan: Int = 1
    }

    SimConfig.withWave.compile {
      val dut = new OneTxnManOneLockTable(sysConf)
      dut.txnMan.io.simPublic()
      dut
    }.doSim("OneTxnManOneLockTable", 99) { dut =>
      // params
      val txnLen = 8
      val txnCnt = 128
      val txnMaxLen = sysConf.maxTxnLen - 1

      dut.clockDomain.forkStimulus(period = 10)

      // data memory
      val axiMem = SimDriver.instAxiMemSim(dut.io.dataAXI, dut.clockDomain, None)

      // cmd memory
      val fNId = (i: Int, j: Int) => i % sysConf.nNode
      val fCId = (i: Int, j: Int) => j % sysConf.nChannel
      val fTId = (i: Int, j: Int) => (i*j+j) % sysConf.nTable
      val fLockID = (i: Int, j: Int) => i*j+j
      val fWLen = (i: Int, j: Int) => 1


      val txnCtx = SimInit.txnEntrySim(txnCnt, txnLen, txnMaxLen)(fNId, fCId, fTId, fLockID, fWLen).toArray
      val cmdAxiMem = SimDriver.instAxiMemSim(dut.io.loadAXI, dut.clockDomain, Some(txnCtx))

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