package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine
import hwsys.util._

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
    val wSendTimeOut = 8 // TimeOut counter inside the NetManager: the batching latency
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
    def nHashValue = (1 << (wHashTable))
    def wLinkList   = 13 // depth of LL
    def wLinkListOffset = 6
    def nLinkListEntry  = (1 << (wLinkList)) 
    def maxLinkListAddr = (nLinkListEntry - 1)
    def maxCheckEmpty = 8 // how many empty waitQ slots should be checked when inserting a new waitEntry
    def nLLEncoderInput = 8
    def wHashValueNW = (1 + wOwnerCnt) // 1-bit lock status (ex/sh)
    def wHashValueBW = (1 + wOwnerCnt + wLinkListOffset + 1) // 1-bit lock status (ex/sh), 1-bit WaitQ valid or not

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
    def nTxnMem    = (nTxnCS * maxTxnLen) // nTxnMem * wLockEntry = space of on-chip mem 
    def wTxnMemAddr = log2Up(nTxnMem)
    def wTxnMemLock = log2Up(wLockEntry) // shift size of one lock entry
    def wTxnMemUnit = log2Up(maxTxnLen) + log2Up(wLockEntry)// shift size of one txn entry 
    def AXI_DATA_BITS = 512
    def AXI_ADDR_BITS = 64
    def AXI_LOAD_BEAT = (maxTxnLen * wLockEntry / AXI_DATA_BITS)
    def AXI_DATA_SIZE = log2Up(512/8) // each beat read in 512-bit data. AXI-Mem data line size = 512-bit e.g., size=2: 32-bit, size=3: 64 bit...
    def LOCK_PER_AXI_DATA = (AXI_DATA_BITS / wLockEntry)
    def MEM_RW_SIZE = 28 // AXI read and write from a 28-bit space for every node. 4-bit channel, 16-bit lockID, + 8-bit=64 Byte RW unit
    def MEM_CH_SIZE = 24 

}

// Lock types: NoLock, Read, Write, ReadAndWrite
case class LockType() extends Bundle {
  val read = Bool() // init False // Error: Try to set initial value of a data that is not a register 
  val write = Bool() // init False
}

case class LockEntryMin(conf: MinSysConfig) extends Bundle {
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
    lkReq.srcNode := srcNodeID
    lkReq.srcTxnMan := txnManID
    lkReq.srcTxnIdx := curTxnIdx
    lkReq.toRelease := release // True: Release lock. False: Get Lock
    lkReq.txnTimeOut := timeOut
    // lkReq.lockIdx    := lkIdx
    lkReq
  }
}

case class LockRequest(conf: MinSysConfig) extends Bundle {
    // copy Lock Entry: 32-bit in MinCase
    val nodeID    = UInt(conf.wNodeID bits)
    val channelID = UInt(conf.wChannelID bits)
    val tableID   = UInt(conf.wTableID bits)
    val lockID    = UInt(conf.wLockID bits)
    val lockType  = LockType() // 28-29 bits
    val rwLength  = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes
    // Lock Request: 16-bit without lockIdx
    val srcNode = UInt(conf.wNodeID bits)
    val srcTxnMan = UInt(conf.wTxnManID bits)
    val srcTxnIdx = UInt(conf.wTxnIdx bits)
    val toRelease = Bool() // True: Release lock. False: Get Lock
    val txnTimeOut = Bool()// True: clear the lock request in WaitQ.

    def toLockResponse(grant: Bool, wait: Bool, abort: Bool, release: Bool): LockResponse = {
      val lkResp = LockResponse(this.conf)
      lkResp.assignSomeByName(this)
      lkResp.granted  := grant
      lkResp.waiting  := wait
      lkResp.aborted  := abort
      lkResp.released := release
      lkResp.tableIdx := 0
      lkResp
    }
    def toWaitEntryBW(): WaitEntryBW = {
      val waitEntry = WaitEntryBW(this.conf)
      waitEntry.assignSomeByName(this)
      waitEntry.nextReqOffset := 0
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

    // Lock Response
    // From Lock Entry details - 20-bit
    val channelID = UInt(conf.wChannelID bits)
    val tableIdx   = UInt(conf.wTableID bits) // 28-31 bits
    val lockID    = UInt(conf.wLockID bits)
    val lockType  = LockType()
    val rwLength  = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes
    // Lock Response Types - one-hot encoding - 4-bit
    val granted  = Bool()
    val waiting  = Bool()
    val aborted  = Bool()
    val released = Bool()
}


case class TxnManAgentIO(conf: MinSysConfig) extends Bundle {
  // Lock Request and Response interface
  val localLockReq,  toRemoteLockReq    = master Stream LockRequest(conf)
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
  io.toRemoteLockReq << StreamArbiterFactory.roundRobin.transactionLock.onArgs(lkReqGetRmt, lkReqRlseRmt)

  // Store TXN workloads
  val MemTxn = Mem(LockEntryMin(conf), conf.nTxnMem) // taskLoad - WRITE, getSend - READ, DataCommit - READ, Release - READ 
  // store locks with Write to commit
  val MemLockWriteLoc = Mem(LockEntryMin(conf), conf.nTxnMem) // faster commit getSend - WRITE, DataCommit - READ
  /* Record the aborted lock needs to wait for all responses even after timeOut...
  ** So I simply add one ownerCnt when abort a lock, so the txnManager can early terminate / release the lock even not receiving the lockGet response
  ** The current manager is sensitive to package loss. If a packet lost, then a Txn may not terminate correctly.
  */
  // record the aborted and waiting locks. Reset to False when taskLoad
  // val rStatusAborted  = Vec(RegInit(False), conf.nTxnMem) // Aborted locks count as released locks. Set when receive response...
  // val rStatusWaiting  = Vec(RegInit(False), conf.nTxnMem) // Waiting locks need to travel the waitQ in lockTable. 

  // context registers NOTE: separate local / remote; lockWait is only useful in debug mode.
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
    val curTxnIdx    = Reg(UInt(log2Up(conf.nTxnCS) bits)).init(0)
    val cntTxnLoaded = Reg(UInt(48 bits)).init(0)
    val loadToAddr   = curTxnIdx ## cntTxnLen.value
    val loadFromAddr = ((cntTxnLoaded + io.loadAddrBase) << ((conf.wMaxTxnLen) + log2Up(conf.wLockEntry / 8))) // load from AXI memory, the unit is one txn entry
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
    //val ar_len = B(conf.AXI_LOAD_BEAT - 1)
    io.loadAXI.ar.addr := loadFromAddr.resized 
    io.loadAXI.ar.id   := 0
    io.loadAXI.ar.len  := conf.AXI_LOAD_BEAT - 1 // how many beats to read in one Txn Entry. e.g., len=0: 1 beat, len=1: 2 beats...
    io.loadAXI.ar.size := conf.AXI_DATA_SIZE 
    io.loadAXI.ar.setBurstINCR()
    io.loadAXI.ar.valid := isActive(RD_AXI) // RD_AXI configs the loadAXI. then LD_TXN loads the whole Txn Entry 
    io.loadAXI.r.ready  := (isActive(LD_TXN) && cntLockPerRead === 0 && ~rAXI_ReadFire) ? True | False

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
      val curLock = LockEntryMin(conf)
      curLock.assignFromBits(loadAXIDataSlice(cntLockPerRead)) // get lock use relative addr: cntLock per Read
      MemTxn.write(loadToAddr.asUInt, curLock, rTxnMemLd) // write this lock entry to MemTxn
      // conditions 
      when(io.loadAXI.r.fire)(rTxnMemLd.set())
      when(cntLockPerRead.willOverflow)(rTxnMemLd.clear()) // clear it to read in next 512-bit
      when(cntTxnLen.willOverflow) { // Now a whole txn entry is loaded
          // update loaded counter
          cntTxnLoaded  := cntTxnLoaded  + 1
          // clear state registers
          rAbort(curTxnIdx).clear()
          rTimeOut(curTxnIdx).clear()
          rLoaded(curTxnIdx).set()
          rGetSent(curTxnIdx).clear()
          rGrantAllLock(curTxnIdx).clear()
          rDataReadLoc(curTxnIdx).clear()
          rDataReadRmt(curTxnIdx).clear()
          rDataWroteLoc(curTxnIdx).clear()
          rDataWroteRmt(curTxnIdx).clear()
          rReleaseSent(curTxnIdx).clear()
          rReleaseDone(curTxnIdx).clear()
          // clear all cnt registers ERROR: cntTimeOut(curTxnIdx).clearAll() and := 0, WIDTH MISMATCH (8 bits <- 0 bits) on (toplevel/txnMan/cntDataWroteRmt_15 :  UInt[8 bits]) := (U"" 0 bits)
          // cntTimeOut(curTxnIdx).clearAll()
          // cntLockGetSentLoc(curTxnIdx).clearAll() 
          // cntLockGetSentRmt(curTxnIdx).clearAll() 
          // cntLockHoldLoc(curTxnIdx).clearAll()
          // cntLockHoldRmt(curTxnIdx).clearAll() 
          // cntLockWaitLoc(curTxnIdx).clearAll() 
          // cntLockWaitRmt(curTxnIdx).clearAll() 
          // cntLockRlseSentLoc(curTxnIdx).clearAll() 
          // cntLockRlseSentRmt(curTxnIdx).clearAll() 
          // cntLockReleasedLoc(curTxnIdx).clearAll() 
          // cntLockReleasedRmt(curTxnIdx).clearAll() 
          // cntLockwReadLoc(curTxnIdx).clearAll() 
          // cntLockwReadRmt(curTxnIdx).clearAll() 
          // cntDataReadLoc(curTxnIdx).clearAll() 
          // cntDataReadRmt(curTxnIdx).clearAll() 
          // cntLockwWriteLoc(curTxnIdx).clearAll() 
          // cntLockwWriteRmt(curTxnIdx).clearAll() 
          // cntDataWroteLoc(curTxnIdx).clearAll()
          // cntDataWroteRmt(curTxnIdx).clearAll() 

          cntTimeOut(curTxnIdx) := U(0, conf.wTimeOut bits)
          cntLockGetSentLoc(curTxnIdx)  := U(0, conf.wLockIdx bits)
          cntLockGetSentRmt(curTxnIdx)  := U(0, conf.wLockIdx bits)
          cntLockHoldLoc(curTxnIdx)     := U(0, conf.wLockIdx bits)
          cntLockHoldRmt(curTxnIdx)     := U(0, conf.wLockIdx bits)
          cntLockWaitLoc(curTxnIdx)     := U(0, conf.wLockIdx bits)
          cntLockWaitRmt(curTxnIdx)     := U(0, conf.wLockIdx bits) 
          cntLockRlseSentLoc(curTxnIdx) := U(0, conf.wLockIdx bits) 
          cntLockRlseSentRmt(curTxnIdx) := U(0, conf.wLockIdx bits) 
          cntLockReleasedLoc(curTxnIdx) := U(0, conf.wLockIdx bits) 
          cntLockReleasedRmt(curTxnIdx) := U(0, conf.wLockIdx bits)
          cntLockwReadLoc(curTxnIdx)    := U(0, conf.wLockIdx bits) 
          cntLockwReadRmt(curTxnIdx)    := U(0, conf.wLockIdx bits) 
          cntDataReadLoc(curTxnIdx)     := U(0, conf.wLockIdx bits) 
          cntDataReadRmt(curTxnIdx)     := U(0, conf.wLockIdx bits) 
          cntLockwWriteLoc(curTxnIdx)   := U(0, conf.wLockIdx bits) 
          cntLockwWriteRmt(curTxnIdx)   := U(0, conf.wLockIdx bits) 
          cntDataWroteLoc(curTxnIdx)    := U(0, conf.wLockIdx bits)
          cntDataWroteRmt(curTxnIdx)    := U(0, conf.wLockIdx bits) 

          when(cntTxnLoaded  === (io.txnNumTotal - 1))(goto(IDLE)) otherwise (goto(CS_TXN))
      } // load one txn finished
    }
  }


  /*******************************************************************
    * component 2: Lock Get Request: Send Get-Lock Requests to local and remote Lock Agents 
    *******************************************************************/
  val aLockGetRequestCenter = new StateMachine {
    val CS_TXN = new State with EntryPoint
    val GET_LEN, SEND_REQ = new State
    // current TxnIdx, ReqIdx, and memory address 
    val curTxnIdx      = Reg(UInt(log2Up(conf.nTxnCS) bits)).init(0)
    val txnLen, reqIdx = Reg(UInt(conf.wMaxTxnLen bits)).init(0)
    val txnMemAddrBase = (curTxnIdx << (conf.wMaxTxnLen))
    val txnMemAddr     = txnMemAddrBase + reqIdx // reqIdx is 0: read in TxnLen, reqIdx > 0: read in lock entry
    val memReadData    = MemTxn.readSync(txnMemAddr)
    // conditions: when to start/end sending lock Get requests
    val lkReqFire       = lkReqGetLoc.fire || lkReqGetRmt.fire
    val startLockGetReq = rLoaded(curTxnIdx) && ~rGetSent(curTxnIdx) && ~rAbort(curTxnIdx)
    val endLockGetReq   = (lkReqFire && (reqIdx === (txnLen))) || rAbort(curTxnIdx) // CS_TXN adds 1 to reqIdx, so the reqIdx should = txnLen
    val isLocal         = memReadData.nodeID === io.nodeIdx
    for (e <- Seq(lkReqGetLoc, lkReqGetRmt))
      e.payload := memReadData.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx.resize(conf.wTxnIdx), False, False, reqIdx) // not toRelease = Get, not timeOut

    CS_TXN.whenIsActive {
      when(startLockGetReq) { // The current read in data is TxnLen
        // txnLen := memReadData.asBits(conf.wMaxTxnLen-1 downto 0).asUInt // The memRead result comes at next cycle
        reqIdx := reqIdx + 1  // Next cycle in SEND_REQ gets the first lock entry
        goto(GET_LEN)
      } otherwise {
        txnLen := 0
        reqIdx.clearAll()
        curTxnIdx := curTxnIdx + 1
      }
    }

    GET_LEN.whenIsActive{
      txnLen := (memReadData.tableID ## memReadData.channelID ## memReadData.nodeID).asUInt.resized
      goto(SEND_REQ)
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
  val lockRequestNormal = cloneOf(io.fromRemoteLockReq)
  val lockReleaseWriteFIFO = StreamFifo(LockRequest(conf), 16) // stream FIFO to tmp store the wrRlse req (later issue after get axi.b) // asynchronous, so that the queue may accept multiple ReleaseWrites, and the main stream is not blocked
  val lockRequestWrote = Stream(LockRequest(conf)) // This is the lock request with write after wrote the data
  val lockRequestDemux = StreamDemux(io.fromRemoteLockReq, isLockReleaseWrite.asUInt, 2)
  lockRequestDemux(0) >> lockRequestNormal
  lockRequestDemux(1) >> lockReleaseWriteFIFO.io.push // fork to both lockReleaseWriteFIFO and axi.aw, // push the lockReleaseWrite request to the FIFO
  // send LockRequest to lock channels from local Get/Release and remote lockRequestNormal / lockReleaseWriteFIFO
  io.localLockReq << StreamArbiterFactory.roundRobin.transactionLock.onArgs(lkReqGetLoc, lkReqRlseLoc, lockRequestNormal, lockRequestWrote)

  val aRemoteLockRequestHandler = new StateMachine{
      val WAIT_REQ = new State with EntryPoint
      val MEM_ADDR, MEM_WRITE, SEND_REQ= new State
      
      val rLockRequestToWrite = RegNextWhen(lockReleaseWriteFIFO.io.pop.payload, lockReleaseWriteFIFO.io.pop.fire)
      lockRequestWrote.payload := rLockRequestToWrite
      lockReleaseWriteFIFO.io.pop.ready := isActive(WAIT_REQ) 
      WAIT_REQ.whenIsActive{
        when(lockReleaseWriteFIFO.io.pop.fire){ // new lockRequest comes from FIFO.pop
          goto(MEM_ADDR) 
        }
      }

      val wrDataQ = cloneOf(io.fromRemoteWrite) // w.valid=wrDataCtrl.valid, wrDataCtrl.ready=w.ready
      wrDataQ <-< io.fromRemoteWrite.queue(16) // Get the remote write data from the writeQ
      wrDataQ.ready    := False
      // write the data from lockReleaseWrite request: one aw config will triger rwLen Writes 
      // val aw_len = U(lockRequestWrote.rwLength -1)
      io.loadAXI.aw.addr := ((lockRequestWrote.lockID << (log2Up(512/8))) + (lockRequestWrote.channelID << (conf.MEM_CH_SIZE))).resized // address: 16-bit + 6 + 2 = 24 bits. So txnMem should start from 1<<25. Writes in diff lockIDs have no conflict.
      io.loadAXI.aw.id   := 0  // not necessary because only use loadAXI Write once
      io.loadAXI.aw.len  := (lockRequestWrote.rwLength -1).resized // aw.length is Read/Write length - 1 because aw.len=0 is 1 write
      io.loadAXI.aw.size := log2Up(512/8) // 512-bit = 64 byte
      io.loadAXI.aw.setBurstINCR()
      io.loadAXI.aw.valid:= isActive(MEM_ADDR) // set AW to config write addr
      // if(conf.axiConf.useStrb){
      //   io.loadAXI.w.payload.strb.setAll()
      // }
      // Error: ASSIGNMENT OVERLAP completely the previous one of (toplevel/txnMan/io_loadAXI_w_payload_strb : out Bits[64 bits])
      MEM_ADDR.whenIsActive{
        when(io.loadAXI.aw.fire){ // AXI address has been sent 
          wrDataQ.ready := True  // read out one wrData
          goto(MEM_WRITE)
        }
      }

      // one aw config trigers rwLen Writes 
      // count when loadAXI write, so cntBeat=nBeatQ[pop]==rwLen 
      val cntBeat = Counter(conf.wRWLength bits, io.loadAXI.w.fire) // SpinalHDL: Counter(bitCount: BitCount[, inc : Bool]) Starts at zero and ends at (1 << bitCount) - 1
      when(io.loadAXI.w.last && io.loadAXI.w.fire) (cntBeat.clear())// clear counter when 
      io.loadAXI.w.data := wrDataQ.payload 
      io.loadAXI.w.last := (cntBeat === lockRequestWrote.rwLength - 1) // cntBeat
      io.loadAXI.w.valid:= isActive(MEM_WRITE)
      io.loadAXI.b.ready.set() // accept AXI.b to pop lockReleaseWriteFIFO: Ensure the data is written before releasing the lock.
      MEM_WRITE.whenIsActive{
        wrDataQ.ready := io.loadAXI.w.ready // sync wrData readout with AXI.w, because AXI.w.valid=True
        when(io.loadAXI.b.fire){ // received AXI feedbacks
          goto(SEND_REQ)
        }
      }

      lockRequestWrote.valid   := isActive(SEND_REQ) // send out the lock Release Request to lockTables
      SEND_REQ.whenIsActive{
        when(lockRequestWrote.fire){ //lockRequest has been sent
          goto(WAIT_REQ)
        }
      }
  }


  /*******************************************************************
    * component 4: Lock Response: accept lock channel response and arbitrate them to local/remote
    *******************************************************************/
  val aLockResponseCenter = new StateMachine {
    val WAIT_RESP = new State with EntryPoint
    val MEM_ADDR, MEM_READ, SEND_REMOTE  = new State

    // demux the lock response into local and remote ones
    val isLocalResponse = io.localLockResp.srcNode === io.nodeIdx
    val curTxnIdx  = io.localLockResp.srcTxnIdx.resize(log2Up(conf.nTxnCS))
    val rCurTxnIdx = RegNextWhen(curTxnIdx, io.localLockResp.fire) // record the previous txnIdx to release it or read data
    val rLkResp    = RegNextWhen(io.localLockResp, io.localLockResp.fire)
    val rFire      = RegNext(io.localLockResp.fire, False)

    /** Txn release cases:
     * 1, send 1 req and aborted (release 0 lock), send reqs and aborted (release locks  = LockGetSent - 1): so aborted lock counts as 1 released lock
     * 2, timeOut: release locks = LockGetSent
     * 3, normal end: release locks = LockGetSent
     */
    val releaseDoneCondition = (rReleaseSent(rCurTxnIdx) || rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)) && rGetSent(rCurTxnIdx) && (cntLockReleasedLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockReleasedRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(releaseDoneCondition) {
      rAbort(rCurTxnIdx).clear()
      rTimeOut(rCurTxnIdx).clear()
      rLoaded(rCurTxnIdx).clear()
      rGetSent(rCurTxnIdx).clear()
      rGrantAllLock(rCurTxnIdx).clear()
      rDataReadLoc(rCurTxnIdx).clear()
      rDataReadRmt(rCurTxnIdx).clear()
      rDataWroteLoc(rCurTxnIdx).clear()
      rDataWroteRmt(rCurTxnIdx).clear()
      rReleaseSent(rCurTxnIdx).clear()
      rReleaseDone(rCurTxnIdx).set()
    }
    val txnDoneCondition = rFire && rLoaded(rCurTxnIdx) && releaseDoneCondition && rLkResp.srcNode === io.nodeIdx
    val txnAbortCondition = rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)
    when(txnDoneCondition){
      when(txnAbortCondition)(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
    val grantedAllCondition = rLoaded(rCurTxnIdx) && rGetSent(rCurTxnIdx) && (cntLockHoldLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockHoldRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(grantedAllCondition)(rGrantAllLock(rCurTxnIdx) := True)
    val readAllCondition = rGetSent(rCurTxnIdx) && (cntDataReadRmt(rCurTxnIdx) === cntLockwReadRmt(rCurTxnIdx)) && (cntDataReadLoc(rCurTxnIdx) === cntLockwReadLoc(rCurTxnIdx))
    when(readAllCondition) { // Using Local-only or Remote-only conditions may cause local or remote read not set / set too early. e.g., 1 remote lock + 80 local lock + 1 remote lock.
      rDataReadRmt(rCurTxnIdx) := True // now set both counters at local and remote response handlers to ensure instant flag set and correctness
      rDataReadLoc(rCurTxnIdx) := True
    }

    io.toRemoteLockResp.payload := rLkResp // The remote response go at this cycle, the Read data sends in next cycle
    io.toRemoteLockResp.valid   := isActive(SEND_REMOTE)
    io.localLockResp.ready      := isActive(WAIT_RESP) // both local and remote response needs MEM_READ
    WAIT_RESP.whenIsActive {
      when(io.localLockResp.fire) {
        switch(isLocalResponse){
          is(True){
            when(io.localLockResp.granted) {
              cntLockHoldLoc(curTxnIdx) := cntLockHoldLoc(curTxnIdx) + 1 // local response, grant + 1
              when(io.localLockResp.lockType.read)(goto(MEM_ADDR)) // issue local Read once get the lock
            } elsewhen(io.localLockResp.waiting) {
              cntLockWaitLoc(curTxnIdx) := cntLockWaitLoc(curTxnIdx) + 1
            } elsewhen(io.localLockResp.aborted) {
              rAbort(curTxnIdx) := True // FIXME: rAbort set conflict
              io.cntLockDenyLoc := io.cntLockDenyLoc + 1
              // Abort is not counted in released lock to simplify the logic, but it increases the txn time.
              // cntLockReleasedLoc(curTxnIdx) := cntLockReleasedLoc(curTxnIdx) + 1 // abort is a kind of release
            } elsewhen(io.localLockResp.released) {
              cntLockReleasedLoc(curTxnIdx) := cntLockReleasedLoc(curTxnIdx) + 1
            } // No need to store the granted/waited locks to release: release == cntLockGetsent to ensure all req are released
          }
          is(False){
            when(io.localLockResp.granted) {
              io.cntRmtLockGrant := io.cntRmtLockGrant + 1
            } elsewhen(io.localLockResp.waiting) {
              io.cntRmtLockWait  := io.cntRmtLockWait  + 1
            } elsewhen(io.localLockResp.aborted) {
              io.cntRmtLockDeny  := io.cntRmtLockDeny  + 1
            } elsewhen(io.localLockResp.released) {
              io.cntRmtLockRelease := io.cntRmtLockRelease + 1
            } 
            goto(SEND_REMOTE)
          }
        }
      }
    }

    SEND_REMOTE.whenIsActive{
      when(io.toRemoteLockResp.fire){
        when(rLkResp.granted && rLkResp.lockType.read){
          goto(MEM_ADDR)
        } otherwise{
          goto(WAIT_RESP)
        }
      }       
    }

    io.dataAXI.ar.addr := ((rLkResp.lockID << (log2Up(512/8))) + (rLkResp.channelID << (conf.MEM_CH_SIZE))).resized 
    io.dataAXI.ar.id   := rLkResp.srcTxnMan.resized
    io.dataAXI.ar.len  := (rLkResp.rwLength - 1).resized
    io.dataAXI.ar.size := log2Up(512 / 8)
    io.dataAXI.ar.setBurstINCR()
    io.dataAXI.ar.valid := isActive(MEM_ADDR)// send the read address
    
    val cntRead = Counter(conf.wRWLength bits, io.dataAXI.r.fire)
    MEM_ADDR.whenIsActive{
      cntRead.clear()
      when(io.dataAXI.ar.fire)(goto(MEM_READ)) // clear the read adress: don't read repeatly
    }

    // read data send to toRemoteRead
    io.toRemoteRead.payload := io.dataAXI.r.data
    io.toRemoteRead.valid   := isActive(MEM_READ) && io.dataAXI.r.valid && (rLkResp.srcNode =/= io.nodeIdx) 
    io.dataAXI.r.ready := True // always accept the data read result in MEM_READ state
    MEM_READ.whenIsActive {
      when(rLkResp.srcNode =/= io.nodeIdx){ // remote data read
        io.dataAXI.r.ready := io.toRemoteRead.ready // FIXED: No gurantee to send out the data
        when(cntRead === rLkResp.rwLength)(goto(WAIT_RESP))    // when data has all read, go back to WAIT_RESP
      } otherwise { // local data read
        when(cntRead === rLkResp.rwLength){
          cntDataReadLoc(rCurTxnIdx) := cntDataReadLoc(rCurTxnIdx) + 1
          goto(WAIT_RESP)    // when data has all read, go back to WAIT_RESP
        }
      }
    }
  }
  
  /*******************************************************************
    * component 5: Lock Response Remote: accept lock response from remote nodes
    *******************************************************************/
  val aRemoteResponseAccepter = new StateMachine {
    val WAIT_RESP           = new State with EntryPoint
    val CONSUME_REMOTE_READ = new State

    val rLkResp    = RegNextWhen(io.fromRemoteLockResp, io.fromRemoteLockResp.fire)
    val curTxnIdx  = io.fromRemoteLockResp.srcTxnIdx.resize(log2Up(conf.nTxnCS))
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
            // Abort is not counted in released lock to simplify the logic, but it increases the txn time.
            // cntLockReleasedRmt(curTxnIdx) := cntLockReleasedRmt(curTxnIdx) + 1 // Now we don't record the number of aborted locks, so all sent locks will be released
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
    val releaseDoneCondition = (rReleaseSent(rCurTxnIdx) || rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)) && rGetSent(rCurTxnIdx) && (cntLockReleasedLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockReleasedRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(releaseDoneCondition) {
      rLoaded(rCurTxnIdx).clear()
      rAbort(rCurTxnIdx).clear()
      rTimeOut(rCurTxnIdx).clear()
      rGetSent(rCurTxnIdx).clear()
      rGrantAllLock(rCurTxnIdx).clear()
      rDataReadLoc(rCurTxnIdx).clear()
      rDataReadRmt(rCurTxnIdx).clear()
      rDataWroteLoc(rCurTxnIdx).clear()
      rDataWroteRmt(rCurTxnIdx).clear()
      rReleaseSent(rCurTxnIdx).clear()
      rReleaseDone(rCurTxnIdx).set()
    }
    val txnDoneCondition = rFire && rLoaded(rCurTxnIdx) && releaseDoneCondition && rLkResp.srcNode === io.nodeIdx
    val txnAbortCondition = rAbort(rCurTxnIdx) || rTimeOut(rCurTxnIdx)
    when(txnDoneCondition){
      when(txnAbortCondition)(io.cntTxnAbt := io.cntTxnAbt + 1) otherwise (io.cntTxnCmt := io.cntTxnCmt + 1)
    }
    val grantedAllCondition = rLoaded(rCurTxnIdx) && rGetSent(rCurTxnIdx) && (cntLockHoldLoc(rCurTxnIdx) === cntLockGetSentLoc(rCurTxnIdx)) && (cntLockHoldRmt(rCurTxnIdx) === cntLockGetSentRmt(rCurTxnIdx))
    when(grantedAllCondition)(rGrantAllLock(rCurTxnIdx) := True)
    val readAllCondition = rGetSent(rCurTxnIdx) && (cntDataReadRmt(rCurTxnIdx) === cntLockwReadRmt(rCurTxnIdx)) && (cntDataReadLoc(rCurTxnIdx) === cntLockwReadLoc(rCurTxnIdx))
    when(readAllCondition) { // Using Local-only or Remote-only conditions may cause local or remote read not set / set too early. e.g., 1 remote lock + 80 local lock + 1 remote lock.
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
    val curTxnIdx = Reg(UInt(log2Up(conf.nTxnCS) bits)).init(0)
    val txnMemAddrBase = (curTxnIdx << (conf.wMaxTxnLen)) // Mem unit is one LockEntry, so base address is TxnIdx * MaxTxnLen
    val commitLockWrite = MemLockWriteLoc.readSync(txnMemAddrBase + cntDataWroteLoc(curTxnIdx)) //
    val rCommitLockWrite = RegNext(commitLockWrite)
    val commitCondition  = rLoaded(curTxnIdx) && rGetSent(curTxnIdx) && rGrantAllLock(curTxnIdx) && ~rDataWroteLoc(curTxnIdx) && ~rAbort(curTxnIdx) && ~rTimeOut(curTxnIdx) && (cntDataWroteLoc(curTxnIdx) < cntLockwWriteLoc(curTxnIdx))
    val setDataWroteLoc  = rLoaded(curTxnIdx) && rGetSent(curTxnIdx) && (cntDataWroteLoc(curTxnIdx) === cntLockwWriteLoc(curTxnIdx))

    CS_TXN.whenIsActive {
      when(commitCondition) {
        goto(LOCAL_AW)
      } otherwise {
        when(setDataWroteLoc)(rDataWroteLoc(curTxnIdx) := True) // move the assignment out of the LOCAL_W state: if 0 write data, then no need to wrote
        curTxnIdx := curTxnIdx + 1
      }
    }

    // ERROR: The memory boundary Index 364527616 out of bounds for length 4096
    // Solution: Use (()) to avoid any unclear data shift
    // io.dataAXI.aw.addr := (commitLockWrite.lockID << (log2Up(512/8)) + commitLockWrite.channelID << conf.MEM_CH_SIZE).resized 
    io.dataAXI.aw.addr := ((commitLockWrite.lockID << (log2Up(512/8))) + (commitLockWrite.channelID << (conf.MEM_CH_SIZE))).resized 
    io.dataAXI.aw.id   := curTxnIdx.resized
    io.dataAXI.aw.len  := (commitLockWrite.rwLength - 1).resized
    io.dataAXI.aw.size := log2Up(512 / 8)
    io.dataAXI.aw.valid:= isActive(LOCAL_AW)
    io.dataAXI.aw.setBurstINCR()
    LOCAL_AW.whenIsActive {
      when(io.dataAXI.aw.fire) {
        goto(LOCAL_W)
      }
    }

    val nBeat = Reg(UInt(8 bits)).init(0)
    io.dataAXI.w.data.setAll() // The data payload is all 11111111s
    io.dataAXI.w.last  := (nBeat === rCommitLockWrite.rwLength - 1)
    io.dataAXI.w.valid := isActive(LOCAL_W) && (nBeat < rCommitLockWrite.rwLength)
    io.dataAXI.b.ready.set() // ready to accept write response
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
        }otherwise{ // all local data has been written/committed
          rDataWroteLoc(curTxnIdx) := True
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
    val GET_ENTRY, RELEASE_LOCK, REMOTE_WRITE = new State
    /** Release conditions
     * 1, normal case: Loaded, LockGetSent, GrantedAllLock, DataReadLocal, DataReadRemote, DataWroteLocal, not ReleaseSent
     * 2. rAbort: Loaded, not ReleaseSent
     * 3. rTimeOut: Loaded, not ReleaseSent
     * To release locks == LockGetSent - Aborted locks...
     * */
    val curTxnIdx      = Reg(UInt(log2Up(conf.nTxnCS) bits)).init(0)
    val txnMemAddrBase = (curTxnIdx << (conf.wMaxTxnLen))
    val reqIdx         = cntLockRlseSentLoc(curTxnIdx) + cntLockRlseSentRmt(curTxnIdx)
    val lockEntryAddr  = txnMemAddrBase + reqIdx + 1 // + 1 because the first entry is the txn length, not a valid lockEntry
    val lockEntry      = MemTxn.readSync(lockEntryAddr)
    val ifNormalRelease  = rGetSent(curTxnIdx) && rGrantAllLock(curTxnIdx) && rDataReadLoc(curTxnIdx) && rDataReadRmt(curTxnIdx) && rDataWroteLoc(curTxnIdx)
    val releaseCondition = rLoaded(curTxnIdx) && (rTimeOut(curTxnIdx) || rAbort(curTxnIdx) || ifNormalRelease) && (~rReleaseSent(curTxnIdx))
    val ifReturnToCSLoc  = (cntLockRlseSentLoc(curTxnIdx) === cntLockGetSentLoc(curTxnIdx) - 1) && (cntLockRlseSentRmt(curTxnIdx) === cntLockGetSentRmt(curTxnIdx))
    val ifReturnToCSRmt  = (cntLockRlseSentLoc(curTxnIdx) === cntLockGetSentLoc(curTxnIdx))     && (cntLockRlseSentRmt(curTxnIdx) === cntLockGetSentRmt(curTxnIdx) - 1)
    val setReleaseSent   = (cntLockRlseSentLoc(curTxnIdx) === cntLockGetSentLoc(curTxnIdx))     && (cntLockRlseSentRmt(curTxnIdx) === cntLockGetSentRmt(curTxnIdx)) && ifNormalRelease
    val setDataWroteRmt  = (cntDataWroteRmt(curTxnIdx) === cntLockwWriteRmt(curTxnIdx)) && ifNormalRelease

    CS_TXN.whenIsActive {
      when(setDataWroteRmt)(rDataWroteRmt(curTxnIdx) := True)
      when(setReleaseSent)(rReleaseSent(curTxnIdx) := True)
      when(releaseCondition) {
        goto(RELEASE_LOCK)
      } otherwise {
        curTxnIdx := curTxnIdx + 1
      }
    }

    // FIXME: check the clock cycles
    val isLocal           = lockEntry.nodeID === io.nodeIdx
    lkReqRlseLoc.payload := lockEntry.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx.resize(conf.wTxnIdx), True, rAbort(curTxnIdx) || rTimeOut(curTxnIdx), reqIdx)
    lkReqRlseRmt.payload := lockEntry.toLockRequest(io.nodeIdx, io.txnManIdx, curTxnIdx.resize(conf.wTxnIdx), True, rAbort(curTxnIdx) || rTimeOut(curTxnIdx), reqIdx)
    lkReqRlseLoc.valid   :=  isLocal && isActive(RELEASE_LOCK)
    lkReqRlseRmt.valid   := ~isLocal && isActive(RELEASE_LOCK)

    RELEASE_LOCK.whenIsActive { // FIXME: how to skip the aborted locks...
      when(lkReqRlseLoc.fire) {
        cntLockRlseSentLoc(curTxnIdx) := cntLockRlseSentLoc(curTxnIdx) + 1
        when(ifReturnToCSLoc){
          rReleaseSent(curTxnIdx) := True
          goto(CS_TXN)
        }
        // fire -> cntSent+1 / Address updated -> read out the next lock Entry, so go back to CS_SWITCH to wait for the read out data
      }
      when(lkReqRlseRmt.fire) {
        cntLockRlseSentRmt(curTxnIdx) := cntLockRlseSentRmt(curTxnIdx) + 1
        when(lockEntry.lockType.write){
          goto(REMOTE_WRITE)
        }otherwise{
          when(ifReturnToCSRmt){
            rReleaseSent(curTxnIdx) := True
            goto(CS_TXN)
          }
        }
      }
    }

    io.toRemoteWrite.valid := isActive(REMOTE_WRITE)
    io.toRemoteWrite.payload.setAll()
    val nBeat = Reg(UInt(conf.wRWLength bits)).init(0)
    REMOTE_WRITE.whenIsActive {
      when(io.toRemoteWrite.fire) {
        nBeat := nBeat + 1
        when(nBeat === lockEntry.rwLength - 1) {
          cntDataWroteRmt(curTxnIdx) := cntDataWroteRmt(curTxnIdx) + 1
          nBeat.clearAll()
          when(ifReturnToCSRmt){
            rReleaseSent(curTxnIdx) := True
            goto(CS_TXN)
          } otherwise{
            goto(RELEASE_LOCK)
          }
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
        Seq(io.cntTxnCmt, io.cntTxnAbt, io.cntTxnLd, io.cntLockLoc, io.cntLockRmt, io.cntLockDenyLoc, io.cntLockDenyRmt, io.cntRmtLockGrant, io.cntRmtLockWait, io.cntRmtLockDeny, io.cntRmtLockRelease).foreach(_.clearAll())
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





