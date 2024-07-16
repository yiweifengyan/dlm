package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine
import hwsys.util._


class NetManagerIO(conf: MinSysConfig) extends Bundle{
  // Interface between host and NetManager
  val start = in Bool()

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
}

class DecoderReqResp(dataLen: Int, select: Int) extends Component {
  val io = new Bundle {
    val in_valid = in Bool()
    val in_ready = out Bool()
    val in_data  = Bits(64 bits)
    val out_valid = out Bool()
    val out_ready = in Bool()
    val out_data  = out(Reg(Bits(dataLen bits)))
    val out_sel   = out(Reg(Bits(select bits)))
  }
  val FSM = new StateMachine{
    val WAIT_DATA = new State with EntryPoint
    val SEND_DATA = new State

    val dataValid = io.in_data(0) ^ io.in_data(1) // lockRequest or lockResponse
    io.in_ready := isActive(WAIT_DATA)
    WAIT_DATA.whenIsActive{
      io.out_data := io.in_data(63 downto 63 - dataLen)
      io.out_sel  := io.in_data(3 + select downto 4)
      when(io.in_valid && dataValid)(goto(SEND_DATA))
    }
    io.out_valid := isActive(SEND_DATA)
    SEND_DATA.whenIsActive{
      when(io.out_ready)(goto(WAIT_DATA))
    }
  }
}

class EncoderReqRespData(conf: MinSysConfig, NUM_SEND_Q_DATA: Int, NUM_SELECT_BITS: Int) extends Component {
  val io = new Bundle {
    val in_request = slave Stream LockRequest(conf)
    val in_response = slave Stream LockResponse(conf)
    val in_dataRead = slave Stream Bits(512 bits)
    val in_dataWrite = slave Stream Bits(512 bits)
    val in_dataSelect = out (Reg(UInt(NUM_SELECT_BITS bits)))
    val out_data = master Stream Bits(512 bits)
    val out_length = out UInt(3 bits)
  }
  val sendQReqs = Reg(Bits(512 bits)).init(0)
  val sendQData = Vec(Reg(Bits(512 bits)), NUM_SEND_Q_DATA) // 5 slot, ready to send
  val timeToSend = Reg(Bool()).init(False) // The time counter shows that it is timeout
  val currentReqs = Reg(UInt(log2Up(512 / 64) bits)).init(0)
  val currentData = Reg(UInt(log2Up(NUM_SEND_Q_DATA) bits)).init(0) // current data slot to load
  val dataPointer = Reg(UInt(log2Up(NUM_SEND_Q_DATA) bits)).init(0) // current data pointer when sending out data
  val timeOutCounter = Reg(UInt(conf.wSendTimeOut bits)).init(0)
  val timeOutStart = Reg(Bool()).init(False)
  when(timeOutStart)(timeOutCounter := timeOutCounter + 1)
  when(timeOutCounter.andR)(timeToSend.set())

  val FSM = new StateMachine{
    val LOAD_REQS = new State with EntryPoint
    val LOAD_DATA, SEND_REQS, SEND_DATA = new State

    io.in_response.ready := isActive(LOAD_REQS)
    io.in_request.ready := isActive(LOAD_REQS) && ~io.in_response.valid // Request has lower priority than Response
    val to_send_reqs = timeToSend || ((io.in_response.fire || io.in_request.fire) && (currentReqs === 7)) // condition: switch to SEND_REQ
    val to_load_data_req = io.in_request.payload.toRelease && ~io.in_request.payload.txnTimeOut && io.in_request.payload.lockType.write && (io.in_request.payload.rwLength > 0)
    val to_load_data_resp = io.in_response.payload.granted && io.in_response.payload.lockType.read && (io.in_response.payload.rwLength > 0)
    val load_from_read = Reg(Bool()).init(False)     // load from READ_DATA or WRITE_DATA?
    val loadLength = Reg (UInt(conf.wRWLength bits)) // one load: how many loading should I run
    val loadCounter = Reg (UInt(conf.wRWLength bits))// one load: counter loading
    LOAD_REQS.whenIsActive{
      when(io.in_response.fire){
        sendQReqs(currentReqs * 64 +  0, 4 bits) := B(1, 4 bits) // B3-B0: 0001 The x(offset: UInt, width bits) Variable part-select of fixed width, offset is LSB index
        sendQReqs(currentReqs * 64 +  4, 4 bits) := io.in_response.payload.srcTxnMan.asBits // where to route this package: srcTxnMan is also target/receiving TxnMan
        sendQReqs(currentReqs * 64 +  8, 8 bits) := B(0, 4 bits) // Fill in with 0s
        sendQReqs(currentReqs * 64 + 16,48 bits) := io.in_response.payload.asBits
        currentReqs := currentReqs + 1
        when(currentReqs === 0)(timeOutStart.set())
        when(to_load_data_req) {
          io.in_dataSelect := io.in_response.payload.srcTxnMan
          load_from_read   := True
          loadLength  := io.in_response.payload.rwLength
          loadCounter := 0
          goto(LOAD_DATA)
        }
      }
      when(io.in_request.fire) {
        sendQReqs(currentReqs * 64 +  0, 4 bits) := B(2, 4 bits) // B3-B0: 0010
        sendQReqs(currentReqs * 64 +  4, 4 bits) := io.in_request.payload.srcTxnMan.asBits // where to route this package: srcTxnMan is also target/receiving TxnMan
        sendQReqs(currentReqs * 64 +  8, 8 bits) := B(0, 4 bits) // Fill in with 0s
        sendQReqs(currentReqs * 64 + 16,48 bits) := io.in_request.payload.asBits
        currentReqs := currentReqs + 1
        when(currentReqs === 0)(timeOutStart.set())
        when(to_load_data_resp){
          io.in_dataSelect := io.in_request.payload.srcTxnMan
          load_from_read   := False
          loadLength  := io.in_request.payload.rwLength
          loadCounter := 0
          goto(LOAD_DATA)
        }
      }
      when(to_send_reqs)(goto(SEND_REQS))
    }

    io.in_dataRead.ready := isActive(LOAD_DATA) && load_from_read
    io.in_dataWrite.ready := isActive(LOAD_DATA) && ~load_from_read
    LOAD_DATA.whenIsActive{
      when(io.in_dataRead.fire){
        sendQData(currentData)(  7 downto 0) := io.in_dataSelect.resize(4) ## B(4, 4 bits) // B3-B0: 0100
        sendQData(currentData)(511 downto 8) := io.in_dataRead.payload(511 downto 8)
      }
      when(io.in_dataWrite.fire){
        sendQData(currentData)(  7 downto 0) := io.in_dataSelect.resize(4) ## B(8, 4 bits) // B3-B0: 1000
        sendQData(currentData)(511 downto 8) := io.in_dataWrite.payload(511 downto 8)
      }
      when(io.in_dataWrite.fire || io.in_dataRead.fire){
        currentData := currentData + 1
        loadCounter := loadCounter + 1
        when(loadCounter === loadLength - 1) {
          when((currentData > 1) || timeToSend) { // currentData=2, actual loaded data =3, now we have 1 reqResp and >=3 data packets: can send out
            goto(SEND_REQS)
          } otherwise {
            goto(LOAD_REQS)
          }
        }
      }
    }

    io.out_data.valid := isActive(SEND_REQS) || isActive(SEND_DATA)
    io.out_length := currentData // The sending out batch is 1 reqResp + data packets. Only data packet num is needed for sneder 
    SEND_REQS.whenIsActive{
      timeToSend.clear() // clear the signals on timeOut counter
      timeOutStart.clear()
      timeOutCounter.clearAll()
      currentReqs.clearAll() // clear the reqRespCounter
      io.out_data.payload := sendQReqs
      when(io.out_data.fire){
        sendQReqs := 0 // flush the content of reqResp
        when(currentData > 0){
          dataPointer.clearAll() // = 0
          goto(SEND_DATA)
        } otherwise{
          goto(LOAD_REQS)
        }
      }
    }
    
    SEND_DATA.whenIsActive{
      io.out_data.payload := sendQData(dataPointer)
      when(io.out_data.fire){
        dataPointer := dataPointer + 1
        when(dataPointer === currentData - 1){
          currentData.clearAll() // reset the currentData
          goto(LOAD_REQS)
        }
      }
    }
  }
}

class PacketSender(portCount: Int, wSendLength: Int) extends Component{
  val io = new Bundle {
    val inputs   = Vec(slave Stream Bits(512 bits), portCount)
    val inLength = Vec(UInt(wSendLength bits))
    val output   = master Stream Bits(512 bits)
  }

  val locked = RegInit(False).allowUnsetRegToAvoidLatch
  val maskProposal = Vec(Bool(),portCount)
  val maskLocked   = Reg(Vec(Bool(),portCount))
  val maskRouted   = Mux(locked, maskLocked, maskProposal) //Mux(cond, outputWhenTrue, outputWhenFalse)
  val toSendData   = MuxOH(maskRouted, io.inLength) // how many data packets to send in current selection
  val sendCounter  = Reg(UInt(wSendLength bits)).init(0)

  val FSM = new StateMachine{
    val SEND_ONE = new State with EntryPoint
    val SEND_OTHERS = new State

    SEND_ONE.whenIsActive{
      when(io.output.valid) {
        maskLocked := maskRouted
      }
      when(io.output.fire){
        sendCounter := toSendData
        when(toSendData > 0){
          locked.set()
          goto(SEND_OTHERS)
        } otherwise {
          locked.clear()
        }
      }
    }

    SEND_OTHERS.whenIsActive{
      when(io.output.fire){
        sendCounter := sendCounter - 1
        when(sendCounter === 1){
          locked.clear()
          goto(SEND_ONE)
        }
      }
    }
  }

  val arbitration = new Area { // Copy from the original StreamArbiter.Arbitration.roundRobin
    for(bitId  <- maskLocked.range){
      maskLocked(bitId) init(Bool(bitId == maskLocked.length-1))
    }
    maskProposal := OHMasking.roundRobin(Vec(io.inputs.map(_.valid)),Vec(maskLocked.last +: maskLocked.take(maskLocked.length-1)))
  }
  io.output.valid := (io.inputs, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
  io.output.payload := MuxOH(maskRouted,Vec(io.inputs.map(_.payload))) // OH is OneHot
  (io.inputs, maskRouted).zipped.foreach(_.ready := _ & io.output.ready)
}

class NetManager(conf: MinSysConfig) extends Component {
  val io = new NetManagerIO(conf)
  // The NetManager Config
  val NUM_NODES = 16
  val NUM_SEND_Q_DATA    = 5
  val NUM_SEND_Q_LEN     = 3 // 3+1 = 4 packets at normal cases
  val NUM_SEND_Q_MAX_LEN = 6
  val NUM_RECVQ_REQS = 8
  val NUM_RECVQ_DATA = 16
  val NUM_DECODERS   = 8
  val NUM_TO_TXNMAN_DATAQ = 8
  val NUM_TO_TXNMAN_RESPQ = 8

  /* ******************************************
  * Decode packets: rdmaSink --> fromRemoteData
  * */
  // 1, MUX rdmaSink into Req/Resp and Read/Write ports
  val recvQReqs = StreamFifo(Bits(512 bits), NUM_RECVQ_REQS)
  val recvQData = StreamFifo(Bits(512 bits), NUM_RECVQ_DATA)
  val resvQSelect = io.rdmaSink.payload(0) || io.rdmaSink.payload(1) // Bit 0: LockReq, Bit 1: Lock Response
  val recvQDemux = StreamDemux(io.rdmaSink, resvQSelect.asUInt, 2)
  recvQDemux(1) >> recvQReqs.io.push
  recvQDemux(0) >> recvQData.io.push

  // 2, extract ResvQ Req/Resp. Remove headers in parallel, then send to TxnMan's recv ports
  val decoderArray = Array.fill(NUM_DECODERS)(new DecoderReqResp(conf.wLockRequest, conf.wTxnManID))
  // recvQReqs.io.pop.ready := decoderArray.reduce((x,y) => x.io.in_ready && y.io.in_ready) // type mismatch; [error]  found   : spinal.core.Bool [error]  required: hwsys.dlm.DecoderReqResp
  // recvQReqs.io.pop.ready := decoderArray(0).ready && decoderArray(1).ready && decoderArray(2).ready && decoderArray(3).ready && decoderArray(4).ready && decoderArray(5).ready && decoderArray(6).ready && decoderArray(7).ready
  val decoderArrayReady = Bits(NUM_DECODERS bits)
  for (i <- 0 until NUM_DECODERS)
    decoderArrayReady(i) := decoderArray(i).io.in_ready
  recvQReqs.io.pop.ready := decoderArrayReady.andR
  // crossBar part 1: 8 req/resp to nTxnMan
  val reqRespDemuxArray = Array.fill(NUM_DECODERS)(new StreamDemux(Bits(conf.wLockRequest bits), conf.nTxnMan))
  decoderArray.zipWithIndex.foreach { case (decoder, idx) =>
    // recvQReqs.io.pop.ready := decoder.io.in_ready
    decoder.io.in_valid  := recvQReqs.io.pop.valid
    decoder.io.in_data   := recvQReqs.io.pop.payload(idx * 64 + 63 downto idx * 64)
    decoder.io.out_ready := reqRespDemuxArray(idx).io.input.ready
    reqRespDemuxArray(idx).io.input.valid   := decoder.io.out_valid
    reqRespDemuxArray(idx).io.input.payload := decoder.io.out_data
    reqRespDemuxArray(idx).io.select        := decoder.io.out_sel.asUInt.resized
  }
  // crossBar part 2: nTxnMan accept 8 req/Resp with lowerFirst priority
  val reqRespArbiterArray = Array.fill(conf.nTxnMan)(new StreamArbiter(Bits(conf.wLockRequest bits), NUM_DECODERS)(StreamArbiter.Arbitration.lowerFirst, StreamArbiter.Lock.none))
  for (i <- 0 until conf.nTxnMan)
    (reqRespDemuxArray.map(_.io.outputs(i)), reqRespArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _) // pipelined
  // separate the lock requests from responses
  val toTxnManReqResps = Array.fill(conf.nTxnMan)(new StreamDemux(Bits(conf.wLockRequest bits), 2))
  (reqRespArbiterArray, toTxnManReqResps).zipped.foreach(_.io.output >> _.io.input)
  toTxnManReqResps.zipWithIndex.foreach{ case (reqResp, idx) =>
    reqResp.io.select := (reqResp.io.input.payload(31) || reqResp.io.input.payload(30) || reqResp.io.input.payload(29) || reqResp.io.input.payload(28)).asUInt
    io.fromRemoteLockResp(idx).transmuteWith(reqResp.io.outputs(0).queue(NUM_TO_TXNMAN_RESPQ))
    io.fromRemoteLockReq(idx).transmuteWith(reqResp.io.outputs(1).queue(NUM_TO_TXNMAN_RESPQ))
  }

  // 3, extract ResvQ Read/Write.
  val recvDataDemux = StreamDemux(recvQData.io.pop, recvQData.io.pop.payload(4).asUInt, 2) // Bit 4: Data Write
  val recvReadDemux = StreamDemux(recvDataDemux(0), recvDataDemux(0).payload(7 downto 4).asUInt, conf.nTxnMan)
  val recvWriteDemux = StreamDemux(recvDataDemux(1), recvDataDemux(1).payload(7 downto 4).asUInt, conf.nTxnMan)
  io.fromRemoteRead.zipWithIndex.foreach{ case(readPort, i) =>
    readPort << recvReadDemux(i).queue(NUM_TO_TXNMAN_DATAQ)
  }
  io.fromRemoteWrite.zipWithIndex.foreach{ case(writePort, i) =>
    writePort << recvWriteDemux(i).queue(NUM_TO_TXNMAN_DATAQ)
  }


  /* ******************************************
    * Sending out data: toRemoteData --> rdmaSource
    * */
  // Crossbars for toRemote Request, Response, DataRead, DataWrite
  // 1, Request: nTxnMan x 1-to-nNode DeMUX + nNode x nTxnMan-to-1 Arbiter
  val toRemoteReqDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(LockRequest(conf), NUM_NODES))
  val toRemoteReqArbiterArray = Array.fill(NUM_NODES)(new StreamArbiter(LockRequest(conf), conf.nTxnMan)(StreamArbiter.Arbitration.roundRobin, StreamArbiter.Lock.none))
  toRemoteReqDemuxArray.zipWithIndex.foreach { case (demux, idx) =>
    demux.io.input << io.toRemoteLockReq(idx) 
    demux.io.select << io.toRemoteLockReq(idx).payload.nodeID
  }
  for (i <- 0 until NUM_NODES)
    (toRemoteReqDemuxArray.map(_.io.outputs(i)), toRemoteReqArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _)
  // 2, Response: nTxnMan x 1-to-nNode DeMUX + nNode x nTxnMan-to-1 Arbiter
  val toRemoteRespDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(LockResponse(conf), NUM_NODES))
  val toRemoteRespArbiterArray = Array.fill(NUM_NODES)(new StreamArbiter(LockResponse(conf), conf.nTxnMan)(StreamArbiter.Arbitration.roundRobin, StreamArbiter.Lock.none))
  toRemoteRespDemuxArray.zipWithIndex.foreach { case (demux, idx) =>
    demux.io.input << io.toRemoteLockResp(idx)
    demux.io.select << io.toRemoteLockResp(idx).payload.srcNode
  }
  for (i <- 0 until NUM_NODES)
    (toRemoteRespDemuxArray.map(_.io.outputs(i)), toRemoteRespArbiterArray(i).io.inputs).zipped.foreach(_ >/-> _)
  // 3, toRemote Data Read: nTxnMan x 1-to-nNode DeMUX + nNode x nTxnMan-to-1 MUX
  val toRemoteReadDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(Bits(512 bits), NUM_NODES))
  val toRemoteReadMUXArray = Array.fill(NUM_NODES)(new StreamMux(Bits(512 bits), conf.nTxnMan))
  toRemoteReadDemuxArray.zipWithIndex.foreach { case (demux, idx) =>
    demux.io.input << io.toRemoteRead(idx)
    demux.io.select << io.toRemoteLockResp(idx).payload.srcNode
  }
  for (i <- 0 until NUM_NODES)
    (toRemoteReadDemuxArray.map(_.io.outputs(i)), toRemoteReadMUXArray(i).io.inputs).zipped.foreach(_ >/-> _)
  // 4, toRemote Data Write: nTxnMan x 1-to-nNode DeMUX + nNode x nTxnMan-to-1 MUX
  val toRemoteWriteDemuxArray = Array.fill(conf.nTxnMan)(new StreamDemux(Bits(512 bits), NUM_NODES))
  val toRemoteWriteMUXArray = Array.fill(NUM_NODES)(new StreamMux(Bits(512 bits), conf.nTxnMan))
  toRemoteWriteDemuxArray.zipWithIndex.foreach { case (demux, idx) =>
    demux.io.input << io.toRemoteWrite(idx)
    demux.io.select << io.toRemoteLockReq(idx).payload.nodeID
  }
  for (i <- 0 until NUM_NODES)
    (toRemoteWriteDemuxArray.map(_.io.outputs(i)), toRemoteWriteMUXArray(i).io.inputs).zipped.foreach(_ >/-> _)
  // 5, encoder: accept toRemote Req, Resp, Read, Write, and serialize them into one stream
  val encoderArray = Array.fill(NUM_NODES)(new EncoderReqRespData(conf, NUM_SEND_Q_DATA, conf.wTxnManID))
  encoderArray.zipWithIndex.foreach { case (encoder, idx) =>
    encoder.io.in_request << toRemoteReqArbiterArray(idx).io.output
    encoder.io.in_response << toRemoteRespArbiterArray(idx).io.output
    toRemoteReadMUXArray(idx).io.select := encoder.io.in_dataSelect
    toRemoteWriteMUXArray(idx).io.select := encoder.io.in_dataSelect
    encoder.io.in_dataRead << toRemoteReadMUXArray(idx).io.output
    encoder.io.in_dataWrite << toRemoteWriteMUXArray(idx).io.output
  }
  // 6, sender: from the encoder array to send data to one RDMA port: RoundRobin + batched sending.
  val sender = new PacketSender(NUM_NODES, log2Up(NUM_SEND_Q_MAX_LEN))
  encoderArray.zipWithIndex.foreach { case (encoder, idx) =>
    sender.io.inputs(idx) << encoder.io.out_data 
    sender.io.inLength(idx) := encoder.io.out_length
  }
  sender.io.output >> io.rdmaSource
}
