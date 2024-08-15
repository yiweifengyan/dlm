package hwsys.dlm.test

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.master
import hwsys.dlm._
import hwsys.sim._
import hwsys.util.Helpers._
import java.io._

class TwoWrapSys(sysConf: MinSysConfig) extends Component {
  val nodeA = new WrapCore(sysConf)
  val nodeB = new WrapCore(sysConf)

  val io = new Bundle {
    // AXI Mem Channels
    val AXI = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan * 2)
    // Txn Summary
    val txnNumTotal = in UInt (32 bits) // total txns to load
    val loadAddrBase = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B. // loading address base. NOTE: unit size 512B
    // Node Info
    val nodeIdx = in UInt(sysConf.wNodeID bits)
    // control signals (wire the input to the top AXIL registers)
    val start  = in Bool()//NOTE: hold for 1 cycle
    val done   = out Vec(Bool(), sysConf.nTxnMan)
    val cntClk = out Vec(UInt(sysConf.wTimeStamp bits), sysConf.nTxnMan)
    val cntTxnCmt, cntTxnAbt, cntTxnLd, cntLockLoc, cntLockRmt, cntLockDenyLoc, cntLockDenyRmt = out Vec(UInt(32 bits), sysConf.nTxnMan)// Local lock analysis
    val cntRmtLockGrant, cntRmtLockWait, cntRmtLockDeny, cntRmtLockRelease = out(Vec(UInt(32 bits)), sysConf.nTxnMan)// Lock received from remote nodes
  }
  txnManA.io.nodeIdx := 0
  txnManA.io.txnManIdx := 1
  txnManA.io.connectSomeByName(io)

  tableA.io.channelIdx := 0
  tableA.io.start := io.start
  txnManA.io.localLockReq  >> tableA.io.lockRequest
  txnManA.io.localLockResp << tableA.io.lockResponse

  txnManA.io.toRemoteLockReq  >> netManA.io.toRemoteLockReq(1)
  txnManA.io.toRemoteLockResp >> netManA.io.toRemoteLockResp(1)
  txnManA.io.toRemoteRead     >> netManA.io.toRemoteRead(1)
  txnManA.io.toRemoteWrite    >> netManA.io.toRemoteWrite(1)

  txnManA.io.fromRemoteLockReq  << netManA.io.fromRemoteLockReq(1) 
  txnManA.io.fromRemoteLockResp << netManA.io.fromRemoteLockResp(1)
  txnManA.io.fromRemoteRead     << netManA.io.fromRemoteRead(1) 
  txnManA.io.fromRemoteWrite    << netManA.io.fromRemoteWrite(1) 

  netManA.io.toRemoteLockReq(0).payload.assignFromBits(B(0, sysConf.wLockRequest bits))
  netManA.io.toRemoteLockReq(0).valid    := False
  netManA.io.toRemoteLockResp(0).payload.assignFromBits(B(0, sysConf.wLockResponse bits))
  netManA.io.toRemoteLockResp(0).valid   := False
  netManA.io.toRemoteRead(0).payload     := B(0, 512 bits)
  netManA.io.toRemoteRead(0).valid       := False
  netManA.io.toRemoteWrite(0).payload    := B(0, 512 bits)
  netManA.io.toRemoteWrite(0).valid      := False

  netManA.io.fromRemoteLockReq(0).ready  := False 
  netManA.io.fromRemoteLockResp(0).ready := False 
  netManA.io.fromRemoteRead(0).ready     := False 
  netManA.io.fromRemoteWrite(0).ready    := False  

  netManA.io.rdmaSource.queue(64) >> netManB.io.rdmaSink
  netManA.io.rdmaSink   << netManB.io.rdmaSource.queue(64)
  /*
    // Interface between TxnManAgent and NetManger
    val toRemoteLockReq = Vec(slave Stream LockRequest(conf), conf.nTxnMan)
    val toRemoteLockResp = Vec(slave Stream LockResponse(conf), conf.nTxnMan)
    val fromRemoteLockReq = Vec(master Stream LockRequest(conf), conf.nTxnMan)
    val fromRemoteLockResp = Vec(master Stream LockResponse(conf), conf.nTxnMan)
    val fromRemoteRead, fromRemoteWrite = Vec(master Stream Bits(512 bits), conf.nTxnMan)
    val toRemoteWrite, toRemoteRead = Vec(slave Stream Bits(512 bits), conf.nTxnMan)
    // Interface between RDMA and NetManager
    val rdmaSink = slave Stream Bits(512 bits)
    val rdmaSource = master Stream Bits(512 bits)
  */

  txnManB.io.nodeIdx   := 1
  txnManB.io.txnManIdx := 1
  txnManB.io.dataAXI <> io.dataAXIB
  txnManB.io.loadAXI <> io.loadAXIB
  txnManB.io.start := io.start
  txnManB.io.txnNumTotal := io.txnNumTotal
  txnManB.io.loadAddrBase:= io.loadAddrBase

  tableB.io.channelIdx := 1
  tableB.io.start := io.start
  txnManB.io.localLockReq  >> tableB.io.lockRequest
  txnManB.io.localLockResp << tableB.io.lockResponse

  txnManB.io.toRemoteLockReq  >> netManB.io.toRemoteLockReq(1)
  txnManB.io.toRemoteLockResp >> netManB.io.toRemoteLockResp(1)
  txnManB.io.toRemoteRead     >> netManB.io.toRemoteRead(1)
  txnManB.io.toRemoteWrite    >> netManB.io.toRemoteWrite(1)

  txnManB.io.fromRemoteLockReq  << netManB.io.fromRemoteLockReq(1) 
  txnManB.io.fromRemoteLockResp << netManB.io.fromRemoteLockResp(1)
  txnManB.io.fromRemoteRead     << netManB.io.fromRemoteRead(1) 
  txnManB.io.fromRemoteWrite    << netManB.io.fromRemoteWrite(1) 

  netManB.io.toRemoteLockReq(0).payload.assignFromBits(B(0, sysConf.wLockRequest bits))
  netManB.io.toRemoteLockReq(0).valid    := False
  netManB.io.toRemoteLockResp(0).payload.assignFromBits(B(0, sysConf.wLockResponse bits))
  netManB.io.toRemoteLockResp(0).valid   := False
  netManB.io.toRemoteRead(0).payload     := B(0, 512 bits)
  netManB.io.toRemoteRead(0).valid       := False
  netManB.io.toRemoteWrite(0).payload    := B(0, 512 bits)
  netManB.io.toRemoteWrite(0).valid      := False

  netManB.io.fromRemoteLockReq(0).ready  := False 
  netManB.io.fromRemoteLockResp(0).ready := False 
  netManB.io.fromRemoteRead(0).ready     := False 
  netManB.io.fromRemoteWrite(0).ready    := False 
}

object CoreNetSim{
  def main(args: Array[String]): Unit = {
    implicit val sysConf = new MinSysConfig {
      override val nNode: Int = 2
      override val nChannel: Int = 2
      override val nTable: Int = 2
      override val nLock: Int = 65536
      override val nTxnMan: Int = 2
    }

    SimConfig.withWave.compile {
      val dut = new TwoTxnManTwoTableTwoNet(sysConf)
      dut.txnManA.io.simPublic()
      dut.txnManB.io.simPublic()
      dut
    }.doSim("TwoTxnManTwoTableTwoNet", 99) { dut =>
      // params
      val txnLen = 30
      val txnCnt = 128
      val txnMaxLen = sysConf.maxTxnLen 

      dut.clockDomain.forkStimulus(period = 10)

      // cmd memory and data memory
      val fNId = (i: Int, j: Int) => i % sysConf.nNode
      val fCId = (i: Int, j: Int) => j % sysConf.nChannel
      val fTId = (i: Int, j: Int) => (i*j+j) % sysConf.nTable
      val fLockID = (i: Int, j: Int) => 16 + j * 32
      val fLockType = (i: Int, j: Int) => 2  //  all locks are Write 
      val fWLen   = (i: Int, j: Int) => 1
      val txnCtx  = SimInit.txnEntrySim(txnCnt, txnLen, txnMaxLen)(fNId, fCId, fTId, fLockID, fLockType, fWLen).toArray
      val cmdAxiMem = SimDriver.instAxiMemSim(dut.io.loadAXI, dut.clockDomain, Some(txnCtx))
      val axiMem    = SimDriver.instAxiMemSim(dut.io.dataAXI, dut.clockDomain, None)
      val cmdAxiMemB = SimDriver.instAxiMemSim(dut.io.loadAXIB, dut.clockDomain, Some(txnCtx))
      val axiMemB    = SimDriver.instAxiMemSim(dut.io.dataAXIB, dut.clockDomain, None)

      // store the txn workload data array to a file to debug.
      val writer = new PrintWriter(new File("output.data"))
      txnCtx.foreach(writer.println)
      writer.close()

      dut.io.start #= false
      // wait the fifo (empty_ptr) to reset
      dut.clockDomain.waitSampling(sysConf.nLock + 100)

      // config
      dut.io.loadAddrBase #= 0
      dut.io.txnNumTotal #= txnCnt

      // start
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      dut.clockDomain.waitSamplingWhere(dut.txnManB.io.done.toBoolean)

      println(s"[txnManA] cntTxnCmt: ${dut.txnManA.io.cntTxnCmt.toBigInt}")
      println(s"[txnManA] cntTxnAbt: ${dut.txnManA.io.cntTxnAbt.toBigInt}")
      println(s"[txnManA] cntTxnLd: ${dut.txnManA.io.cntTxnLd.toBigInt}")
      println(s"[txnManA] cntClk: ${dut.txnManA.io.cntClk.toBigInt}")
      println(s"[txnManB] cntTxnCmt: ${dut.txnManB.io.cntTxnCmt.toBigInt}")
      println(s"[txnManB] cntTxnAbt: ${dut.txnManB.io.cntTxnAbt.toBigInt}")
      println(s"[txnManB] cntTxnLd: ${dut.txnManB.io.cntTxnLd.toBigInt}")
      println(s"[txnManB] cntClk: ${dut.txnManB.io.cntClk.toBigInt}")
    }
  }
}