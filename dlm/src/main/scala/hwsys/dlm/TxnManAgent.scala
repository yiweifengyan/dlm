trait MinSysConfig {
    // system params
    val nNode : Int    // 4-bit, <=16
    val nChannel : Int // 4-bit, <=16
    val nTable: Int    // 4-bit, <=16
    val nLock : Int    // 16-bit, <=65536
    val nTxnMan : Int  // 4-bit, <=16
    val nTxnCS    = 16 // concurrent txn count, limited by axi arid (6 bits) <= 64
    val maxTxnLen = 32 // max len of each txn (include the txnHd)
    val wMaxTxnLen= log2Up(maxTxnLen) 
    val wTimeOut  = 24 // Timeout counter
    val wTimeStamp = 40

    // bit-width to represent the values.
    def wNodeID   = 4 // =nNode
    def wChannelID= 4 // =nChannel
    def wTableID  = 4 // =nTable
    def wLockID   = 16 //=nLock
    def wTxnManID = 4 // =nTxnMan
    def wTxnIdx   = 6 // =nTxnCS
    def wLockIdx   = 8  // lkIdx in one Txn, for OoO response

    // For Lock Entry, Lock Request, and Lock Response
    def wLockEntry= 32
    def wLockType = 2
    def wRWLength = 2
    def wLockRequest = 56
    def wLockResponse= 32
    // Shift Offsets for Txn Entry - 32-bit TxnEntry
    def sNodeID  =0
    def sChannel =4
    def sTable   =8
    def sLockID  =12
    def sLockType=28
    def sRWLength=30

    // Lock table details
    val wOwnerCnt = 8
    def wHashTable  = 16 // 16-bit address is the same as lockKey width
    def nHashValue = 1 << wHashTable
    def wLinkList   = 13 // depth of LL
    def wLinkListAddr = 14
    def wLinkListOffset = 6
    def nLinkListTail = 4
    def maxLinkListAddr = 1 << wLinkList + (1 << wLinkListOffset) * nLinkListTail - 1
    def nLinkListEntry = 1 << wLinkList + (1 << wLinkListOffset) * (nLinkListTail + 1)
    def maxCheckEmpty = 8 // how many empty waitQ slots should be checked when inserting a new waitEntry
    def nLLEncoderInput = 8
    def wHashValueNW = 1 + wOwnerCnt // 1-bit lock status (ex/sh)
    def wHashValueBW = 1 + wOwnerCnt + wLinkListOffset + 1 // 1-bit lock status (ex/sh), 1-bit WaitQ valid or not

    // CC mode: "NW (no wait)", "BW (bounded wait)", "TSO (timestamp ordering)"
    // def ccProt = "NW"
    def ccProt = "BW"
    // def ccProt = "TSO"

    // val wChSize = 28 // 256MB of each channel (used as offset with global addressing)
    // val wTupLenPow = 2 // len(tuple)= 2^wLen; maxLen = 64B << 7 = 8192 B
    // onFly control
    // val wMaxTupLen = 2 // 64 << 2

    val axiConf = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 6, // max=6, should = conf.wTxnIdx
    useStrb = true,
    useBurst = true,
    useId = true,
    useLock = false,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useLen = true
    )

    // The TxnMem in TxnMan and AXI Mem constants
    def nTxnMem    = nTxnCS * maxTxnLen // nTxnMem * wLockEntry = space of on-chip mem 
    def wTxnMemAddr = log2Up(nTxnMem)
    def wTxnMemLock = log2Up(wLockEntry) // shift size of one lock entry
    def wTxnMemUnit = log2Up(maxTxnLen * wLockEntry) // shift size of one txn entry 
    def AXI_DATA_BITS = 512
    def AXI_ADDR_BITS = 64
    def AXI_LOAD_BEAT = maxTxnLen * wLockEntry / AXI_DATA_BITS
    def AXI_DATA_SIZE = log2Up(512/8) // each beat read in 512-bit data. AXI-Mem data line size = 512-bit e.g., size=2: 32-bit, size=3: 64 bit...
    def LOCK_PER_AXI_DATA = AXI_DATA_BITS / wLockEntry
    def MEM_RW_SIZE = 28 // AXI read and write from a 28-bit space for every node. 4-bit channel, 16-bit lockID, + 8-bit=64 Byte RW unit
    def MEM_CH_SIZE = 24 

}

// Lock types: NoLock, Read, Write, ReadAndWrite
case LockType extends Bundle {
  val read = Bool() init False
  val write = Bool() init False
}

case class LockEntry(conf: MinSysConfig) extends Bundle {
  val nodeID    = UInt(conf.wNodeID bits)
  val channelID = UInt(conf.wChannelID bits)
  val tableID   = UInt(conf.wTableID bits)
  val lockID    = UInt(conf.wLockID bits)
  val lockType  = LockType()
  val rwLength  = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes

  def toLockRequest(srcNodeID: UInt, txnManID: UInt, curTxnIdx: UInt, release: Bool, timeOut: Bool, lkIdx: UInt): LockRequest = {
    val lkReq = LockRequest(this.conf) // process in txnMan, so false
    lkReq.assignSomeByName(this)
    // Lock Request: 16-bit + 8-bit lockIdx
    lkReq.srcNode = srcNodeID
    lkReq.srcTxnMan = txnManID
    lkReq.srcTxnIdx = curTxnIdx
    lkReq.toRelease = release // True: Release lock. False: Get Lock
    lkReq.txnTimeOut = timeOut
    // lkReq.lockIdx    = lkIdx
    lkReq
  }
}

case class LockRequest(conf: MinSysConfig) extends Bundle {
    // copy Lock Entry: 32-bit in MinCase
    val nodeID    = UInt(conf.wNodeID bits)
    val channelID = UInt(conf.wChannelID bits)
    val tableID   = UInt(conf.wTableID bits)
    val lockID    = UInt(conf.wLockID bits)
    val lockType  = LockType()
    val rwLength  = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes
    // Lock Request: 24-bit
    val srcNode = UInt(conf.wNodeID bits)
    val srcTxnMan = UInt(conf.wTxnManID bits)
    val srcTxnIdx = UInt(conf.wTxnIdx bits)
    val toRelease = Bool() // True: Release lock. False: Get Lock
    val txnTimeOut = Bool()// True: clear the lock request in WaitQ.
    // val lockIdx = UInt(conf.wLockIdx bits) // which lock in the lockIdx

    def toLockResponse(grant: Bool, wait: Bool, abort: Bool, release: Bool): LockResponse = {
      val lkResp = LockResponse(this.conf)
      lkResp.assignSomeByName(this)
      lkResp.granted  = grant
      lkResp.waiting  = wait
      lkResp.aborted  = abort
      lkResp.released = release
      lkResp
    }
    def toWaitEntryBW(): WaitEntryBW = {
      val waitEntry = WaitEntryBW(this.conf)
      waitEntry.assignSomeByName(this)
      waitEntry.nextReqOffset = 0
      waitEntry
    }
}

case class LockResponse(conf: MinSysConfig) extends Bundle {
    // Lock Request: 24-bit
    val srcNode = UInt(conf.wNodeID bits)
    val srcTxnMan = UInt(conf.wTxnManID bits)
    val srcTxnIdx = UInt(conf.wTxnIdx bits)
    val toRelease = Bool() // True: Release lock. False: Get Lock
    val txnTimeOut = Bool()// True: clear the lock request in WaitQ.
    // val lockIdx = UInt(conf.wLockIdx bits) // which lock in the lockIdx

    // Lock Response
    // From Lock Entry details - 4-bit
    val channelID = UInt(conf.wChannelID bits)
    val lockID    = UInt(conf.wLockID bits)
    val lockType  = LockType()
    val rwLength  = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes
    // Lock Response Types - one-hot encoding - 4-bit
    val granted  = Bool()
    val waiting  = Bool()
    val aborted  = Bool()
    val released = Bool()
}

package hwsys.dlm

import hwsys.dlm.{LkReq, LkResp, LkT, LockRespType, LockTableBW, LockTableIO, SysConfig, WaitEntryBW}
import hwsys.util._

case class TxnManAgentIO(conf: MinSysConfig) extends Bundle {
  // Lock Request and Response interface
  val localLockReq,  toRemoteLockReq   = master Stream LockRequest(conf)
  val localLockResp, fromRemoteLockResp = slave Stream LockResponse(conf)
  val fromRemoteLockReq = slave Stream LockRequest(conf)
  val toRemoteLockResp = master Stream LockResponse(conf)

  // Data Read and Write interface
  val fromRemoteRead, fromRemoteWrite = slave Stream Bits(512 bits)
  val toRemoteWrite, toRemoteRead    = master Stream Bits(512 bits)

  // AXI buses
  val dataAXI = master(Axi4(conf.axiConf)) // Local data Read + Write, Remote data Read
  val loadAXI = master(Axi4(conf.axiConf)) // load Txn Entry, Remote data Write

  // txnMan config
  val nodeIdx = in UInt (conf.wNodeID bits)     // avoid confusing with lkReq/Resp.nodeID
  val txnManIdx = in UInt (conf.wTxnManID bits)
  val txnNumTotal = in UInt (32 bits)  // total txns to load
  val loadAddrBase = in UInt (32 bits) // loading address base. NOTE: unit size 512B

  // control signals (wire the input to the top AXIL registers)
  val start = in Bool()  //NOTE: hold for 1 cycle
  val done = out(Reg(Bool())).init(False)
  val cntTxnCmt, cntTxnAbt, cntTxnLd, cntLockLoc, cntLockRmt, cntLockDenyLoc, cntLockDenyRmt = out(Reg(UInt(32 bits))).init(0) // Local lock analysis
  val cntRmtLockGrant, cntRmtLockWait, cntRmtLockDeny, cntRmtLockRelease = out(Reg(UInt(32 bits))).init(0) // Lock received from remote nodes
  val cntClk = out(Reg(UInt(conf.wTimeStamp bits))).init(0)

  def setDefault() = {
    if (conf.axiConf.useStrb) {
      dataAXI.w.strb.setAll()
      loadAXI.w.strb.setAll()
    }
  }
}



class TxnManAgent(conf: MinSysConfig) extends Component with RenameIO {

  val io = TxnManAgentIO(conf)
  io.setDefault()

  // lkGet and lkRlse are be arbitrated and sent to io
  val lkReqGetLoc, lkReqRlseLoc, lkReqGetRmt, lkReqRlseRmt = Stream(LockRequest(conf))
  io.toRemoteLockReq << StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetRmt, lkReqRlseRmt)
  for (e <- Seq(lkReqGetLoc, lkReqRlseLoc, lkReqGetRmt, lkReqRlseRmt))
    e.valid := False

  // Store TXN workloads
  val MemTxn = Mem(LockEntry(conf), conf.nTxnMem)
  // store locks with Write to commit
  val MemLockWriteLoc = Mem(conf.wLockIdx, conf.nTxnMem)
  val MemLockWriteRmt = Mem(conf.wLockIdx, conf.nTxnMem)

  // context registers NOTE: separate local / remote;
  val cntLockGetSentLoc, cntLockHoldLoc, cntLockWaitLoc, cntLockRlseSentLoc, cntLockReleasedLoc = Vec(Reg(UInt(conf.wLockIdx bits)).init(0), conf.nTxnCS)
  val cntLockGetSentRmt, cntLockHoldRmt, cntLockWaitRmt, cntLockRlseSentRmt, cntLockReleasedRmt = Vec(Reg(UInt(conf.wLockIdx bits)).init(0), conf.nTxnCS)
  val cntLockwReadLoc, cntDataReadLoc, cntLockwWriteLoc, cntDataWroteLoc = Vec(Reg(UInt(conf.wLockIdx bits)).init(0), conf.nTxnCS)
  val cntLockwReadRmt, cntDataReadRmt, cntLockwWriteRmt, cntDataWroteRmt = Vec(Reg(UInt(conf.wLockIdx bits)).init(0), conf.nTxnCS)

  // status register
  val cntTimeOut = Vec(Reg(UInt(conf.wTimeOut bits)).init(0), conf.nTxnCS)
  val rLoaded, rGetSent, rGrantAllLock, rDataReadLoc, rDataReadRmt, rDataWroteLoc, rDataWroteRmt, rReleaseSent, rAbort, rTimeOut = Vec(RegInit(False), conf.nTxnCS)
  val rReleaseDone = Vec(RegInit(True), conf.nTxnCS) // init to True, to trigger the first txnMem load and issue

  // io.done: all txn rlseDone; all txn loaded; set done only once
  when(rReleaseDone.andR && io.cntTxnLd === io.txnNumTotal && ~io.done)(io.done.set())


  /*******************************************************************
    * component 1: Task Loader: load Txns from AXiMem to on-chip txnMem 
    *******************************************************************/
  val aTaskLoader = new StateMachine {
    val IDLE = new State with EntryPoint
    val CS_TXN, RD_AXI, LD_TXN = new State
    // For a single mem load
    val rTxnMemLd     = RegInit(False) // memory is loaded in this cycle
    val rAXI_ReadFire = RegNext(io.loadAXI.r.fire, False)
    val rAXI_ReadData = RegNextWhen(io.loadAXI.r.data, io.loadAXI.r.fire) 
    val loadAXIDataSlice = rAXI_ReadData.subdivideIn(conf.LOCK_PER_AXI_DATA slices) // one 512-bit memory read contains 16x32-bit lock entries
    val cntLockPerRead = Counter(log2Up(conf.LOCK_PER_AXI_DATA) bits, rTxnMemLd)     // iterate on the 16 locks in one 512-bit memory read
    val cntTxnLen      = Counter(conf.wMaxTxnLen bits, rTxnMemLd) // iterate on all locks of one txn entry. always works on MaxTxnLen
    // current Txn Index and where to load
    val curTxnIdx    = Reg(UInt(conf.wTxnIdx bits)).init(0)
    val loadToAddr   = curTxnIdx << conf.wMaxTxnLen + cntTxnLen  // MemTxn's unit is one lock entry. 
    val loadFromAddr = (cntTxnLoaded + io.loadAddrBase) << conf.wTxnMemUnit // load from AXI memory, the unit is one txn entry
    val cntTxnLoaded = Reg(UInt(48 bits)).init(0)
    io.cntTxnLd     := cntTxnLoaded.resized

    // IDLE: wait start signal
    IDLE.whenIsActive {
      // reset regs
      curTxnIdx.clearAll()
      rTxnMemLd.clear()
      cntTxnLen.clear()
      cntLockPerRead.clear()
      when(io.start){
        cntTxnLoaded.clearAll()
        goto(CS_TXN)
      }
    }

    // CS_TXN: find a TXN slot that needs reloading: ReleaseDone but not Loaded
    CS_TXN.whenIsActive {
      when(rReleaseDone(curTxnIdx) && ~rLoaded(curTxnIdx)) { // ReleaseDone is cleared after loaded, rLoaded is unnecessary
        goto(RD_AXI)
      } otherwise {
        curTxnIdx := curTxnIdx + 1
      }
    }

    // RD_AXI: configs the loadAXI.ar
    io.loadAXI.ar.addr := loadFromAddr.resized 
    io.loadAXI.ar.id   := 0
    io.loadAXI.ar.len  := (conf.AXI_LOAD_BEAT - 1).resized // how many beats to read in one Txn Entry. e.g., len=0: 1 beat, len=1: 2 beats...
    io.loadAXI.ar.size := conf.AXI_DATA_SIZE 
    io.loadAXI.ar.setBurstINCR()
    io.loadAXI.ar.valid:= isActive(RD_AXI) // RD_AXI configs the loadAXI. then LD_TXN loads the whole Txn Entry 
    io.loadAXI.r.ready := (isActive(LD_TXN) && cntLockPerRead === 0 && ~rAXI_ReadFire) ? True | False

    RD_AXI.whenIsActive {
      when(io.loadAXI.ar.fire) {
        rTxnMemLd.clear()
        cntTxnLen.clear()
        cntLockPerRead.clear()
        goto(LD_TXN)
      }
    }

    // load txnMem
    LD_TXN.whenIsActive {
      // extract the locks from read_data one-by-one
      val curLock = LockEntry(conf)
      curLock.assignFromBits(loadAXIDataSlice(cntLockPerRead)) // get lock use relative addr: cntLock per Read
      MemTxn.write(loadToAddr, curLock, rTxnMemLd) // write this lock entry to MemTxn
      // conditions 
      when(io.loadAXI.r.fire)(rTxnMemLd.set())
      when(cntLockPerRead.willOverflow)(rTxnMemLd.clear()) // clear it to read in next 512-bit
      when(cntTxnLen.willOverflow) { // Now a whole txn entry is loaded
          for (e <- Seq(rReleaseDone, rGetSent, rGrantAllLock, rDataReadLoc, rDataReadRmt, rDataWroteLoc, rDataWroteRmt, rReleaseSent, rAbort, rTimeOut )) {
              e(curTxnIdx).clear()
          }
          // clear all cnt registers
          for (e <- Seq(cntLockGetSentLoc, cntLockHoldLoc, cntLockWaitLoc, cntLockRlseSentLoc, cntLockReleasedLoc, cntLockwReadLoc, cntDataReadLoc, cntLockwWriteLoc, cntDataWroteLoc,
                        cntLockGetSentRmt, cntLockHoldRmt, cntLockWaitRmt, cntLockRlseSentRmt, cntLockReleasedRmt, cntLockwReadRmt, cntDataReadRmt, cntLockwWriteRmt, cntDataWroteRmt)) {
              e(curTxnIdx) := U(0, conf.wLockIdx bits)
          }
          cntTimeOut(curTxnIdx) := U(0, conf.wTimeOut bits)
          // update loaded counter
          cntTxnLoaded  := cntTxnLoaded  + 1
          rLoaded(curTxnIdx).set()
          
          when(cntTxnLoaded  === (io.txnNumTotal - 1))(goto(IDLE)) otherwise (goto(CS_TXN))
      } // load one txn finished
    }
  }


  /*******************************************************************
    * component 2: Lock Get Request: Send Get-Lock Requests to local and remote Lock Agents 
    *******************************************************************/
  val aLockGetRequestCenter = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val SEND_REQ = new State
    // current TxnIdx, ReqIdx, and memory address 
    val curTxnIdx      = Reg(UInt(conf.wTxnIdx bits)).init(0)
    val txnLen, reqIdx = Reg(UInt(conf.wMaxTxnLen bits)).init(0)
    val txnMemAddrBase = curTxnIdx << conf.wMaxTxnLen
    val txnMemAddr     = txnMemAddrBase + reqIdx // reqIdx is 0: read in TxnLen, reqIdx > 0: read in lock entry
    val memReadData    = MemTxn.readSync(txnMemAddr)
    // conditions: when to start/end sending lock Get requests
    val lkReqFire       = lkReqGetLoc.fire || lkReqGetRmt.fire
    val startLockGetReq = rLoaded(curTxnIdx) && ~rGetSent(curTxnIdx) && ~rAbort(curTxnIdx)
    val endLockGetReq   = (lkReqFire && (reqIdx === (txnLen - 1))) || rAbort(curTxnIdx)
    val isLocal         = memReadData.nodeID === io.nodeIdx
    for (e <- Seq(lkReqGetLoc, lkReqGetRmt))
      e.payload := memReadData.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx, False, False, reqIdx) // not toRelease = Get, not timeOut

    CS_TXN.whenIsActive {
      when(startLockGetReq) { // The current read in data is TxnLen
        txnLen := memReadData.asBits(conf.wMaxTxnLen - 1 downto 0).asUInt
        reqIdx := reqIdx + 1  // Next cycle in SEND_REQ gets the first lock entry
        goto(SEND_REQ)
      } otherwise {
        reqIdx.clearAll()
        curTxnIdx := curTxnIdx + 1
      }
    }

    lkReqGetLoc.valid :=  isLocal && isActive(SEND_REQ) // issue lkReq to local / remote ports
    lkReqGetRmt.valid := ~isLocal && isActive(SEND_REQ)
    SEND_REQ.whenIsActive {
      when(lkReqFire) (reqIdx := reqIdx + 1) // switch to next lock entry
      switch(isLocal) {
        is(True){
          when(lkReqFire) {
            cntLockGetSentLoc(curTxnIdx) := cntLockGetSentLoc(curTxnIdx) + 1
            io.cntLockLoc := io.cntLockLoc + 1
            when(memReadData.lockType.read)(cntLockwReadLoc(curTxnIdx) := cntLockwReadLoc(curTxnIdx) + 1) // Count Read Locks
            when(memReadData.lockType.write){ // Count Write Locks
              MemLockWriteLoc.write(txnMemAddrBase + cntLockwWriteLoc(curTxnIdx), memReadData)
              cntLockwWriteLoc(curTxnIdx) := cntLockwWriteLoc(curTxnIdx) + 1
            }
          }
        }
        is(False){
          when(lkReqFire) {
            cntLockGetSentRmt(curTxnIdx) := cntLockGetSentRmt(curTxnIdx) + 1
            io.cntLockRmt := io.cntLockRmt + 1
            when(memReadData.lockType.read) (cntLockwReadRmt(curTxnIdx) := cntLockwReadRmt(curTxnIdx) + 1)
            when(memReadData.lockType.write) {
              MemLockWriteRmt.write(txnMemAddrBase + cntLockwWriteRmt(curTxnIdx), memReadData)
              cntLockwWriteRmt(curTxnIdx) := cntLockwWriteRmt(curTxnIdx) + 1
            }
          }
        }
      }
      // NOTE: lkReq of next Txn OR if abort, stop issue the req
      when(endLockGetReq) { // NOTE: the state jump with rAbort here may cause vld without fire -> the subsequent arb should be `nolock`
        rGetSent(curTxnIdx).set()
        reqIdx.clearAll()
        goto(CS_TXN)
      }
    }
  }


  /*******************************************************************
    * component 3: Remote Lock Request Handler: transfer remote lock requests into local ones, write data using loadAXI.w
    *******************************************************************/
  // demux the io.fromRemoteLockReq (if wrRlse, to Fifo, else to ltReq)
  val isLockReleaseWrite = io.fromRemoteLockReq.toRelease && (io.fromRemoteLockReq.lockType.write) && ~io.fromRemoteLockReq.txnTimeOut // NOTE: not aborted or timeout txn
  val lockReleaseWrite, lockRequestNormal = cloneOf(io.fromRemoteLockReq)
  val lockReleaseWriteFIFO = StreamFifo(LockRequest(conf), 8) // stream FIFO to tmp store the wrRlse req (later issue after get axi.b)
  val lockRequestDemux = StreamDemux(io.fromRemoteLockReq, isLockReleaseWrite.asUInt, 2)
  lockRequestDemux(0) >> lockRequestNormal
  lockRequestDemux(1) >> lockReleaseWrite // fork to both lockReleaseWriteFIFO and axi.aw
  // send LockRequest to lock channels from local Get/Release and remote lockRequestNormal / lockReleaseWriteFIFO
  io.localLockReq << StreamArbiterFactory.roundRobin.noLock.onArgs(lkReqGetLoc, lkReqRlseLoc, lockRequestNormal, lockReleaseWriteFIFO.io.pop.continueWithToken(io.loadAXI.b.fire, 8))

  val aRemoteLockRequestHandler = new Area {
      val (reqFork1, reqFork2) = StreamFork2(lockReleaseWrite, synchronous=False) // asynchronous, so that the queue may accept multiple ReleaseWrites, and the main stream is not blocked
      lockReleaseWriteFIFO.io.push << reqFork1 // push the lockReleaseWrite request to the FIFO

      // write the data from lockReleaseWrite request: one aw config will triger rwLen Writes 
      io.loadAXI.aw.arbitrationFrom(reqFork2) // aw.valid=reqFork2.valid, reqFolk2.realdy=aw.ready
      io.loadAXI.aw.addr := (reqFork2.lockID << (log2Up(512/8)+conf.wRWLength) + reqFolk2.channelID << conf.MEM_CH_SIZE).resized // address: 16-bit + 6 + 2 = 24 bits. So txnMem should start from 1<<25. Writes in diff lockIDs have no conflict.
      io.loadAXI.aw.id   := 0  // not necessary because only use loadAXI Write once
      io.loadAXI.aw.len  := reqFork2.rwLength -1 // aw.length is Read/Write length - 1 because aw.len=0 is 1 write
      io.loadAXI.aw.size := log2Up(512/8) // 512-bit = 64 byte
      io.loadAXI.aw.setBurstINCR()
      if(conf.axiConf.useStrb){
        io.loadAXI.w.payload.strb.setAll()
      }

      // one aw config trigers rwLen Writes 
      // axi.aw / axi.w is individually processed (nBeat should be fifo)
      val nBeatQ = StreamFifoLowLatency(UInt(conf.wRWLength bits), 8)
      nBeatQ.io.push.payload := reqFolk2.rwLength
      nBeatQ.io.push.valid   := reqFork2.fire // when reqFork2 details are used to config the aw, record the current RWLength
      nBeatQ.io.pop.ready    := io.loadAXI.w.last && io.loadAXI.w.fire  // pop the RWLength when all data is written
      // count when loadAXI write, so cntBeat=nBeatQ[pop]==rwLen 
      val cntBeat = Counter(conf.wRWLength bits, io.loadAXI.w.fire) // SpinalHDL: Counter(bitCount: BitCount[, inc : Bool]) Starts at zero and ends at (1 << bitCount) - 1
      when(io.loadAXI.w.last && io.loadAXI.w.fire) (cntBeat.clear())// clear counter when 
      
      val wrDataQ = cloneOf(io.fromRemoteWrite) 
      wrDataQ << io.fromRemoteWrite.queue(8) // Get the remote write data from the writeQ
      val wrDataQCtrl = wrDataQ.continueWithToken(reqFork2.fire, io.loadAXI.w.last && io.loadAXI.w.fire, 8)
      io.loadAXI.w.arbitrationFrom(wrDataQCtrl) // w.valid=wrDataCtrl.valid, wrDataCtrl.ready=w.ready
      io.loadAXI.w.data := wrDataQCtrl.payload
      io.loadAXI.w.last := (cntBeat === nBeatQ.io.pop.payload) // cntBeat==nBeatQ.pop
      io.loadAXI.b.ready.set() // accept AXI.b to pop lockReleaseWriteFIFO: Ensure the data is written before releasing the lock.
  }


  /*******************************************************************
    * component 4: Lock Response: accept lock channel response and arbitrate them to local/remote
    *******************************************************************/
  val aLockResponseCenter = new StateMachine {
    val WAIT_RESP = new State with EntryPoint
    val MEM_READ = new State

    // demux the lock response into local and remote ones
    val isLocalResponse = io.localLockResp.srcNode === io.nodeIdx
    val lockResponseDemux = StreamDemux(io.localLockResp, isLocalResponse.asUInt, 2)
    val lockRespBuff = cloneOf(io.localLockResp)
    lockResponseDemux(0) >> io.toRemoteLockResp // The remote response go at this cycle, the Read data sends in next cycle
    lockResponseDemux(1) >> lockRespBuff 

    io.localLockResp.ready := isActive(WAIT_RESP) // both local and remote response needs MEM_READ
    val curTxnIdx  = io.localLockResp.srcTxnIdx
    val rCurTxnIdx = RegNextWhen(curTxnIdx, io.localLockResp.fire) // record the previous txnIdx to release it or read data
    val rLkResp    = RegNextWhen(io.localLockResp, io.localLockResp.fire)
    val rFire      = RegNext(io.localLockResp.fire, False)

    WAIT_RESP.whenIsActive {
      when(io.localLockResp.fire) {
          when(io.localLockResp.granted) {
            when(isLocalResponse)(cntLockHoldLoc(curTxnIdx) := cntLockHoldLoc(curTxnIdx) + 1) // local response, grant + 1
            when(io.localLockResp.LockType.read)(goto(MEM_READ)) // issue local Read once get the lock
          } elsewhen(io.localLockResp.waiting && isLocalResponse) {
            cntLockWaitLoc(curTxnIdx) := cntLockWaitLoc(curTxnIdx) + 1
          } elsewhen(io.localLockResp.aborted && isLocalResponse) {
            rAbort(curTxnIdx) := True // FIXME: rAbort set conflict
            io.cntLockDenyLoc := io.cntLockDenyLoc + 1
            cntLockReleasedLoc(curTxnIdx) := cntLockReleasedLoc(curTxnIdx) + 1 // abort is a kind of release
          } elsewhen(io.localLockResp.released && isLocalResponse) {
            cntLockReleasedLoc(curTxnIdx) := cntLockReleasedLoc(curTxnIdx) + 1
          } // No need to store the granted/waited locks to release: release == cntLockGetsent to ensure all req are released
      }
    }

    io.dataAXI.ar.addr := (rLkResp.lockID << (log2Up(512/8)+conf.wRWLength) + rLkResp.channelID << conf.MEM_CH_SIZE).resized 
    io.dataAXI.ar.id   := rLkResp.txnManIdx
    io.dataAXI.ar.len  := rLkResp.rwLength - 1
    io.dataAXI.ar.size := log2Up(512 / 8)
    io.dataAXI.ar.setBurstINCR()
    io.dataAXI.r.ready := True // always accept the data read result in MEM_READ state
    // read data send to toRemoteRead
    io.toRemoteRead.payload := io.dataAXI.r.data
    io.toRemoteRead.valid   := isActive(MEM_READ) && io.dataAXI.r.fire && (rLkResp.srcNode =/= io.nodeIdx)
    
    val cntRead = Counter(conf.wRWLength bits, io.dataAXI.r.fire)
    MEM_READ.onEntry{
      io.dataAXI.ar.valid := True // send the read address
      cntRead.clear()
      }
    MEM_READ.whenIsActive {
      when(io.dataAXI.ar.fire)(io.dataAXI.ar.valid := False) // clear the read adress: don't read repeatly
      when(cntRead === rLkResp.rwLength)(goto(WAIT_RESP))    // when data has all read, go back to WAIT_RESP
    }

    /** Txn release cases:
     * 1, send 1 req and aborted (release 0 lock), send reqs and aborted (release locks  = LockGetSent - 1): so aborted lock counts as 1 released lock
     * 2, timeOut: release locks = LockGetSent
     * 3, normal end: release locks = LockGetSent
     */
    val releaseCondition = (rReleaseSent(rCurTxnIdx) || rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)) && (cntLockReleasedLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockReleasedRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(rFire && releaseCondition) {
      rReleaseDone(rCurTxnIdx).set()
      rLoaded(rCurTxnIdx).clear()
      when(rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
    val grantedAllCondition = (cntLockHoldLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockHoldRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(rFire && grantedAllCondition)(rGrantAllLock(rCurTxnIdx) := True)
    val readAllCondition = rGetSent && cntDataReadRmt(rCurTxnIdx) === cntLockwReadRmt(rCurTxnIdx) && cntDataReadLoc(rCurTxnIdx) === cntLockwReadLoc(rCurTxnIdx)
    when(rFire && readAllCondition) { // Using Local-only or Remote-only conditions may cause local or remote read not set / set too early. e.g., 1 remote lock + 80 local lock + 1 remote lock.
      rDataReadRmt(rCurTxnIdx) := True // now set both counters at local and remote response handlers to ensure instant flag set and correctness
      rDataReadLoc(rCurTxnIdx) := True
    }
  }
  
  /*******************************************************************
    * component 5: Lock Response Remote: accept lock response from remote nodes
    *******************************************************************/
  val aRemoteResponseAccepter = new StateMachine {
    val WAIT_RESP           = new State with EntryPoint
    val CONSUME_REMOTE_READ = new State

    val rLkResp    = RegNextWhen(io.fromRemoteLockResp, io.fromRemoteLockResp.fire)
    val curTxnIdx  = io.fromRemoteLockResp.srcTxnIdx
    val rCurTxnIdx = RegNextWhen(curTxnIdx, io.fromRemoteLockResp.fire)
    val rFire      = RegNext(io.fromRemoteLockResp.fire, False)

    io.fromRemoteLockResp.ready := isActive(WAIT_RESP)
    WAIT_RESP.whenIsActive {
      when(io.fromRemoteLockResp.fire) {
          when(io.fromRemoteLockResp.granted) { // note: ooo arrive
            cntLockHoldRmt(curTxnIdx) := cntLockHoldRmt(curTxnIdx) + 1
            when(io.fromRemoteLockResp.lockType.read) (goto(CONSUME_REMOTE_READ))
          }elsewhen(io.fromRemoteLockResp.waiting) {
            cntLockWaitRmt(curTxnIdx) := cntLockWaitRmt(curTxnIdx) + 1
          }elsewhen(io.fromRemoteLockResp.aborted) {
            rAbort(curTxnIdx) := True // FIXME: rAbort set conflict
            io.cntLockDenyRmt := io.cntLockDenyRmt + 1
            cntLockReleasedRmt(curTxnIdx) := cntLockReleasedRmt(curTxnIdx) + 1
          }elsewhen(io.fromRemoteLockResp.released) {
            cntLockReleasedRmt(curTxnIdx) := cntLockReleasedRmt(curTxnIdx) + 1
          }
      }
    }

    val nBeat = Reg(UInt(conf.wRWLength bits)).init(0)
    io.fromRemoteRead.ready := isActive(CONSUME_REMOTE_READ)
    CONSUME_REMOTE_READ.whenIsActive { // REMOTE_Read data come with the lkResp, consume it!
      when(io.fromRemoteRead.fire) {
        nBeat := nBeat + 1
        when(nBeat === (rLkResp.rwLength - 1)) {
          nBeat.clearAll()
          cntDataReadRmt(rCurTxnIdx) := cntDataReadRmt(rCurTxnIdx) + 1
          goto(WAIT_RESP)
        }
      }
    }
    /** Txn release cases:
    * 1, send 1 req and aborted (release 0 lock), send reqs and aborted (release locks  = LockGetSent - 1): so aborted lock counts as 1 released lock
    * 2, timeOut: release locks = LockGetSent
    * 3, normal end: release locks = LockGetSent
    */
    val releaseCondition = (rReleaseSent(rCurTxnIdx) || rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)) && (cntLockReleasedLoc(rCurTxnIdx) === cntlockGetLoc(rCurTxnIdx)) && (cntLockReleasedRmt(rCurTxnIdx) === cntlockGetRmt(rCurTxnIdx))
    when(rFire && releaseCondition) {
      rReleaseDone(rCurTxnIdx).set()
      rLoaded(rCurTxnIdx).clear()
      when(rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx))(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
    val grantedAllCondition = (cntLockHoldLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockHoldRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(rFire && grantedAllCondition)(rGrantAllLock(rCurTxnIdx) := True)
    val readAllCondition = rGetSent && cntDataReadRmt(rCurTxnIdx) === cntLockwReadRmt(rCurTxnIdx) && cntDataReadLoc(rCurTxnIdx) === cntLockwReadLoc(rCurTxnIdx)
    when(rFire && readAllCondition){ // Using Local-only or Remote-only conditions may cause local or remote read not set / set too early. e.g., 1 remote lock + 10 local lock + 1 remote lock.
      rDataReadRmt(rCurTxnIdx) := True // now set both counters at local and remote response handlers to ensure instant flag set and correctness
      rDataReadLoc(rCurTxnIdx) := True
    }
  }

  /** *****************************************************************
   * component 6: Txn Commit: issue local Writes during txn commit
   * ***************************************************************** */
  val aTxnCommitLocalWriter = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val LOCAL_AW, LOCAL_W = new State
    /** Commit condition
     * 1. Loaded, lockGetSent, GrantAllLock, but not DataWroteLoc
     * 2. no abort
     * 3. no timeout
     * */
    val curTxnIdx = Reg(UInt(conf.wTxnIdx bits)).init(0)
    val txnMemAddrBase = curTxnIdx << conf.wMaxTxnLen // Mem unit is one LockEntry, so base address is TxnIdx * MaxTxnLen
    val commitLockWrite = MemLockWriteLoc.readSync(txnMemAddrBase + cntDataWroteLoc(curTxnIdx)) //
    val rCommitLockWrite = RegNext(commitLockWrite)
    val commitCondition  = rLoaded(curTxnIdx) && rGetSent(curTxnIdx) && rGrantAllLock(curTxnIdx) && ~rDataWroteLoc(curTxnIdx) && ~rAbort(curTxnIdx) && ~rTimeOut(curTxnIdx)

    CS_TXN.whenIsActive {
      when(commitCondition) {
        goto(LOCAL_AW)
      } otherwise {
        curTxnIdx := curTxnIdx + 1
      }
    }

    io.dataAXI.aw.addr := (commitLockWrite.lockID << (log2Up(512/8)+conf.wRWLength) + commitLockWrite.channelID << conf.MEM_CH_SIZE).resized
    io.dataAXI.aw.id   := curTxnIdx
    io.dataAXI.aw.len  := commitLockWrite.rwLength - 1
    io.dataAXI.aw.size := log2Up(512 / 8)
    io.dataAXI.aw.valid:= isActive(LOCAL_AW)
    io.dataAXI.aw.setBurstINCR()
    LOCAL_AW.whenIsActive {
      when(io.dataAXI.aw.fire) {
        goto(LOCAL_W)
      }
    }

    io.dataAXI.w.data.setAll() // The data payload is all 11111111s
    io.dataAXI.w.last  := (nBeat === rCommitLockWrite.rwLength - 1)
    io.dataAXI.w.valid := isActive(LOCAL_W) && (nBeat < rCommitLockWrite.rwLength)
    io.dataAXI.b.ready.set() // ready to accept write response
    val nBeat = Reg(UInt(8 bits)).init(0)
    LOCAL_W.whenIsActive {
      when(io.dataAXI.w.fire) {
        nBeat := nBeat + 1
        when(io.dataAXI.w.last) {
          cntDataWroteLoc(curTxnIdx) := cntDataWroteLoc(curTxnIdx) + 1
        }
      }
      when(nBeat === rCommitLockWrite.rwLength){ // FIXME: to confirm in simulation: change states in the next cycle, so that the commitLockWrite is ready when goto LOCAL_AW
        nBeat.clearAll()
        when(cntDataWroteLoc(curTxnIdx) < cntLockwWriteLoc(curTxnIdx)) { // if there are more DataWrites to issue
          goto(LOCAL_AW)
        }otherwise{
          rDataWroteLoc(curTxnIdx) := True // all local data has been written/committed
          goto(CS_TXN)
        }
      }
    }
  }

  /** *****************************************************************
   * component 7: Txn Release: release all local and remote locks, and issue remote Writes
   * ***************************************************************** */
  val aLockReleaseCenter = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val RELEASE_LOCK, REMOTE_WRITE = new State
    /** Release conditions
     * 1, normal case: Loaded, LockGetSent, GrantedAllLock, DataReadLocal, DataReadRemote, DataWroteLocal, not ReleaseSent
     * 2. rAbort: Loaded, not ReleaseSent
     * 3. rTimeOut: Loaded, not ReleaseSent
     * To release locks == LockGetSent - Aborted locks...
     * */
    val curTxnIdx      = Reg(UInt(conf.wTxnId bits)).init(0)
    val txnMemAddrBase = curTxnIdx << conf.wMaxTxnLen
    val reqIdx         = cntLockRlseSentLoc(curTxnIdx) + cntLockRlseSentRmt(curTxnIdx)
    val lockEntry      = lkMemLoc.readSync(txnMemAddrBase + reqIdx)
    val normalReleaseCondition = rGetSent(curTxnIdx) && rGrantAllLock(curTxnIdx) && rDataReadLoc(curTxnIdx) && rDataReadRmt(curTxnIdx) && rDataWroteLoc(curTxnIdx)
    val releaseCondition       = rLoaded(curTxnIdx) && (rTimeOut(curTxnIdx) || rAbort(curTxnIdx) || normalReleaseCondition) && ~rReleaseSent(curTxnIdx)
    val isLocal                = lockEntry.nodeID === io.nodeIdx

    CS_TXN.whenIsActive {
      when(releaseCondition) {
        goto(RELEASE_LOCK)
      } otherwise {
        curTxnIdx := curTxnIdx + 1
      }
    }

    lkReqRlseLoc.payload := lockEntry.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx, True, rAbort(curTxnIdx) || rTimeOut(curTxnIdx), reqIdx)
    lkReqRlseRmt.payload := lockEntry.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx, True, rAbort(curTxnIdx) || rTimeOut(curTxnIdx), reqIdx)
    lkReqRlseLoc.valid   :=  isLocal && isActive(RELEASE_LOCK)
    lkReqRlseRmt.valid   := ~isLocal && isActive(RELEASE_LOCK)

    RELEASE_LOCK.whenIsActive { // FIXME: how to skip the aborted locks...
      when(lkReqRlseLoc.fire) {
        cntLockRlseSentLoc(curTxnIdx) := cntLockRlseSentLoc(curTxnIdx) + 1
        when((cntLockRlseSentLoc(curTxnIdx) === cntLockGetSentLoc(curTxnIdx) - 1) && (cntLockRlseSentRmt(curTxnIdx) === cntLockGetSentRmt(curTxnIdx)))(rReleaseSent(curTxnIdx) := True)
        goto(CS_TXN) // fire -> cntSent+1 / Address updated -> read out the next lock Entry, so go back to CS_SWITCH to wait for the read out data
      }
      when(lkReqRlseRmt.fire) {
        cntLockRlseSentRmt(curTxnIdx) := cntLockRlseSentRmt(curTxnIdx) + 1
        when((cntLockRlseSentLoc(curTxnIdx) === cntLockGetSentLoc(curTxnIdx)) && (cntLockRlseSentRmt(curTxnIdx) === cntLockGetSentRmt(curTxnIdx) - 1))(rReleaseSent(curTxnIdx) := True)
        switch(lockEntry.lockType.write){
          is(True) goto(REMOTE_WRITE)
          is(False)(goto(CS_TXN))
        }
      }
    }

    io.wrRmt.valid := isActive(REMOTE_WRITE)
    io.wrRmt.payload.setAll()
    val nBeat = Reg(UInt(conf.wRWLength bits)).init(0)
    REMOTE_WRITE.whenIsActive {
      when(io.wrRmt.fire) {
        nBeat := nBeat + 1
        when(nBeat === lockEntry.rwLength - 1) {
          nBeat.clearAll()
          goto(CS_TXN)
        }
      }
    }
  }

  /** *****************************************************************
   * component 8: Time Counter, and reset logic
   * ***************************************************************** */
  val aTimeOutCounter = new StateMachine {
    val IDLE = new State with EntryPoint
    val COUNT = new State

    IDLE.whenIsActive {
      cntTimeOut.foreach(_.clearAll())
      rTimeOut.foreach(_.clear())
      when(io.start) {
        io.done.clear()// clear status reg
        io.cntClk.clearAll()
        Seq(io.cntTxnCmt, io.cntTxnAbt, io.cntTxnLd, io.cntLockLoc, io.cntLockRmt, io.cntLockDenyLoc, io.cntLockDenyRmt).foreach(_.clearAll())
        goto(COUNT)
      }
    }

    COUNT.whenIsActive {
      // start counter if reqDone
      (rGetSent, rGrantAllLock, cntTimeOut).zipped.foreach((a,b,c) => {
        when(a && ~b) (c := c + 1)
      })
      (cntTimeOut, rTimeOut).zipped.foreach((a,b) => {
        when(a.andR) {  // cntTimeout.AND_ALL_Bits
          b.set()
        }
      })
      io.cntClk := io.cntClk + 1
      when(io.done)(goto(IDLE))
    }
  }
}





