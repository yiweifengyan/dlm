package hwsys.dlm


class WrapCore(conf: MinSysConfig) extends Component {
  val txnManArray = Array.fill(conf.nTxnMan)(new TxnManCS(conf))
  val lockChArray = Array.fill(conf.nChannel)(new LtCh(conf))

  // Assign index and connect control signals
  
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
