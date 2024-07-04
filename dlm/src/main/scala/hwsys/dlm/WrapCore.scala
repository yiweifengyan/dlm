package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine

class CoreIO(sysConf: MinSysConfig) extends Bundle {
  val AXI = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan * 2)
  val txnNumTotal = in UInt (32 bits)
  val loadAddrBase = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B

  val nodeIdx = in UInt(sysConf.wNodeID bits)
  val start  = in Bool()
  val done   = out Vec(Bool(), sysConf.nTxnMan)
  val cntClk = out Vec(UInt(sysConf.wTimeStamp bits), sysConf.nTxnMan)
  val cntTxnCmt, cntTxnAbt, cntTxnLd, cntLockLoc, cntLockRmt, cntLockDenyLoc, cntLockDenyRmt = out Vec(UInt(32 bits), sysConf.nTxnMan)
}

class WrapCore(conf: MinSysConfig) extends Component {
  val io = new CoreIO(conf)
  val txnManArray = Array.fill(conf.nTxnMan)(new TxnManAgent(conf))
  val lockChArray = Array.fill(conf.nChannel)(new LockChannelBW(conf))

  // Assign index and connect control signals
  txnManArray.foreach { i =>
    i.io.nodeIdx := io.nodeIdx
    i.io.start := io.start
    i.io.txnNumTotal := io.txnNumTotal
  }

  (txnManArray, io.loadAddrBase).zipped.foreach(_.io.loadAddrBase <> _)
  (txnManArray, io.done).zipped.foreach(_.io.done <> _)
  (txnManArray, io.cntTxnCmt).zipped.foreach(_.io.cntTxnCmt <> _)
  (txnManArray, io.cntTxnAbt).zipped.foreach(_.io.cntTxnAbt <> _)
  (txnManArray, io.cntTxnLd).zipped.foreach(_.io.cntTxnLd <> _)
  (txnManArray, io.cntClk).zipped.foreach(_.io.cntClk <> _)
  (txnManArray, io.cntLockLoc).zipped.foreach(_.io.cntLockLoc <> _)
  (txnManArray, io.cntLockRmt).zipped.foreach(_.io.cntLockRmt <> _)
  (txnManArray, io.cntLockDenyLoc).zipped.foreach(_.io.cntLockDenyLoc <> _)
  (txnManArray, io.cntLockDenyRmt).zipped.foreach(_.io.cntLockDenyRmt <> _)

  // txnMan connects to part of io vec
  txnManArray.zipWithIndex.foreach { case (txnMan, idx) =>
    txnMan.io.txnManIdx <> idx
    txnMan.io.loadAXI <> io.AXI(idx *2)
    txnMan.io.dataAXI <> io.AXI(idx *2 + 1)
  }

  lockChArray.zipWithIndex.foreach { case (lockCh, idx) =>
    lockCh.io.channelIdx <> idx
    lockCh.io.start <> io.start
  }
  
  // Crossbar 1: txnMan to lockCh --> lockRequest. 
  // DeMux each request, get nTxnMan * nCH streams
  val reqDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(LockRequest(conf), conf.nChannel))
  (txnManArray, reqDemuxArray).zipped.foreach(_.io.localLockReq >> _.io.input)
  (txnManArray, reqDemuxArray).zipped.foreach(_.io.localLockReq.payload.channelID <> _.io.select)
  // Arbitrate nTxnManx streams into 1 to feed nCH
  val reqArbiterArray = Array.fill(conf.nChannel)(StreamArbiterFactory.roundRobin.build(LockRequest(conf), conf.nTxnMan))
  for (i <- 0 until conf.nChannel)
    (reqDemuxArray.map(_.io.outputs(i)), reqArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined
  (lockChArray, reqArbiterArray).zipped.foreach(_.io.lockRequest << _.io.output)

  // Crossbar 2: lockCh to txnMan --> lockResponse.
  // DeMux each response, get nTxnMan * nCH streams
  val respDemuxArray = Array.fill(conf.nChannel)(new StreamDemux(LockResponse(conf), conf.nTxnMan))
  (lockChArray, respDemuxArray).zipped.foreach(_.io.lockResponse >> _.io.input)
  (lockChArray, respDemuxArray).zipped.foreach(_.io.lockResponse.payload.srcTxnMan <> _.io.select)
  // Arbitrate nCH streams into 1 to feed nTxnMan
  val respArbiterArray = Array.fill(conf.nTxnMan)(StreamArbiterFactory.roundRobin.build(LockResponse(conf), conf.nChannel))
  for (i <- 0 until conf.nTxnMan)
    (respDemuxArray.map(_.io.outputs(i)), respArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined
  (txnManArray, respArbiterArray).zipped.foreach(_.io.localLockResp << _.io.output)

  // TODO: Test WrapCore
  // TODO: Add Communication Manager

}
