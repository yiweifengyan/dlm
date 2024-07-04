package hwsys.dlm

class CoreIO(implicit sysConf: MinSysConfig) extends Bundle {
  val AXI = Vec(master(Axi4(sysConf.axiConf)), sysConf.nTxnMan * 2)
  val txnNumTotal = in UInt (32 bits)
  val cmdAddrOffs = in Vec(UInt(32 bits), sysConf.nTxnMan) //NOTE: unit size 64B

  val nodeId = in UInt(sysConf.wNId bits)
  val start  = in Bool()
  val done   = out Vec(Bool(), sysConf.nTxnMan)
  val cntClk = out Vec(UInt(sysConf.wTimeStamp bits), sysConf.nTxnMan)
  val cntTxnCmt, cntTxnAbt, cntTxnLd, cntLockLoc, cntLockRmt, cntLockDenyLoc, cntLockDenyRmt = out Vec(UInt(32 bits), sysConf.nTxnMan)
}

class WrapCore(conf: MinSysConfig) extends Component {
  val txnManArray = Array.fill(conf.nTxnMan)(new TxnManCS(conf))
  val lockChArray = Array.fill(conf.nChannel)(new LtCh(conf))

  // Assign index and connect control signals
  txnManAry.foreach { i =>
    i.io.nodeId := io.nodeId
    i.io.start := io.start
    i.io.txnNumTotal := io.txnNumTotal
  }

  (txnManAry, io.cmdAxi).zipped.foreach(_.io.cmdAxi <> _)
  (txnManAry, io.cmdAddrOffs).zipped.foreach(_.io.cmdAddrOffs <> _)
  (txnManAry, io.done).zipped.foreach(_.io.done <> _)
  (txnManAry, io.cntTxnCmt).zipped.foreach(_.io.cntTxnCmt <> _)
  (txnManAry, io.cntTxnAbt).zipped.foreach(_.io.cntTxnAbt <> _)
  (txnManAry, io.cntTxnLd).zipped.foreach(_.io.cntTxnLd <> _)
  (txnManAry, io.cntClk).zipped.foreach(_.io.cntClk <> _)
  (txnManAry, io.cntLockLoc).zipped.foreach(_.io.cntLockLoc <> _)
  (txnManAry, io.cntLockRmt).zipped.foreach(_.io.cntLockRmt <> _)
  (txnManAry, io.cntLockDenyLoc).zipped.foreach(_.io.cntLockDenyLoc <> _)
  (txnManAry, io.cntLockDenyRmt).zipped.foreach(_.io.cntLockDenyRmt <> _)

  // txnMan connects to part of io vec
  txnManAry.zipWithIndex.foreach { case (txnMan, idx) =>
    txnMan.io.txnManId <> idx
    txnMan.io.axi <> io.axi(idx)
    txnMan.io.lkReqLoc >/-> ltMCh.io.lt(idx).lkReq
    txnMan.io.lkRespLoc <-/< ltMCh.io.lt(idx).lkResp
  }

  ltMCh.io.nodeId := io.nodeId
  
  // Crossbar 1: txnMan to lockCh --> lockRequest. 
  // DeMux each request, get nTxnMan * nCH streams
  val reqDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(LockRequest(conf), conf.nChannel))
  (txnManArray, reqDemuxArray).zipped.foreach(_.io.lkReqLoc >> _.io.input)
  (txnManArray, reqDemuxArray).zipped.foreach(_.io.lkReqLoc.payload.channelID <> _.io.select)
  // Arbitrate nTxnManx streams into 1 to feed nCH
  val reqArbiterArray = Array.fill(conf.nChannel)(StreamArbiterFactory.roundRobin.build(LockRequest(conf), conf.nTxnMan))
  for (i <- 0 until conf.nChannel)
    (reqDemuxArray.map(_.io.outputs(i)), reqArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined
  (lockChArray, reqArbiterArray).zipped.foreach(_.io.lkReq << _.io.output)

  // Crossbar 2: lockCh to txnMan --> lockResponse.
  // DeMux each response, get nTxnMan * nCH streams
  val respDemuxArray = Array.fill(conf.nChannel)(new StreamDemux(LockResponse(conf), conf.nTxnMan))
  (lockChArray, respDemuxArray).zipped.foreach(_.io.lkResp >> _.io.input)
  (lockChArray, respDemuxArray).zipped.foreach(_.io.lkResp.payload.srcTxnMan <> _.io.select)
  // Arbitrate nCH streams into 1 to feed nTxnMan
  val respArbiterArray = Array.fill(conf.nTxnMan)(StreamArbiterFactory.roundRobin.build(LockResponse(conf), conf.nChannel))
  for (i <- 0 until conf.nTxnMan)
    (respDemuxArray.map(_.io.outputs(i)), respArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined
  (txnManArray, respArbiterArray).zipped.foreach(_.io.lkRespLoc << _.io.output)

  // TODO: Test WrapCore
  // TODO: Add Communication Manager

}
