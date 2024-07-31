package hwsys.dlm

import spinal.core.{UInt, _}
import spinal.core.Mem
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm.StateMachine
import hwsys.util._

//case class LockRespType() extends Bundle{
//  // Lock Response Types - one-hot encoding - 4-bit
//  val granted = Bool()
//  val waiting = Bool()
//  val aborted = Bool()
//  val released = Bool()
//}

// Hash Table value
case class HashValueBW(conf: MinSysConfig) extends Bundle{
  val exclusive  = Bool() // .init(False) // sh,ex // Error: Try to set initial value of a data that is not a register
  val waitQValid = Bool() // if the waitQ ptr valid, also used to indicate if there's lkReq in waiting queue
  val ownerCnt   = UInt(conf.wOwnerCnt bits)
  val waitQAddr  = UInt(conf.wLinkListOffset bits)
  def toUInt : UInt = {
    this.asBits.asUInt
  }
}

// LinkList WaitQ Entry
case class WaitEntryBW(conf: MinSysConfig) extends Bundle{
  // from Lock Request
  val srcNode   = UInt(conf.wNodeID bits)
  val srcTxnMan = UInt(conf.wTxnManID bits)
  val srcTxnIdx = UInt(conf.wTxnIdx bits)
  // from Lock Entry
  val lockType = LockType()
  val rwLength = UInt(conf.wRWLength bits) // read/write size = rwLength * 64 Bytes
  // the next Lock Request
  val nextReqOffset = UInt(conf.wLinkListOffset bits)
  def toUInt : UInt = {
    this.asBits.asUInt
  }
  def toLockResponse(channelIdx: UInt, lockAddr: UInt, grant: Bool, abort: Bool, release: Bool): LockResponse ={
    val lkResp = LockResponse(this.conf)
    lkResp.assignSomeByName(this)
    lkResp.channelID := channelIdx
    lkResp.tableIdx := 0 // Not used. This item is here to make Req-Resp have same 48-bits: easier depacket
    lkResp.lockID  := lockAddr
    lkResp.granted := grant
    lkResp.waiting := False
    lkResp.aborted := abort
    lkResp.released := release
    lkResp
  }
}

class LockTableBWIO(conf: MinSysConfig) extends Bundle{
  val start = in Bool() 
  val channelIdx = in UInt(conf.wChannelID bits)
  val lockRequest  = slave  Stream(LockRequest(conf))
  val lockResponse = master Stream(LockResponse(conf))
}

class LockTableBWait(conf: MinSysConfig) extends Component {
  val io = new LockTableBWIO(conf)
  val ht = new Mem(HashValueBW(conf), conf.nHashValue)
  val ll = new Mem(WaitEntryBW(conf), conf.nLinkListEntry) // Mem[8192*24 bits]
  // io.lockRequest.setBlocked()
  // ht.reset()
  // ll.reset()
  // ll.reset(), ll.clear(), ll.clearAll() cannot zero the memory

  val rLockReq  = RegNextWhen(io.lockRequest.payload, io.lockRequest.fire) // Current Lock Request
  val hashEntry = Reg(HashValueBW(conf)) // current corresponding Lock
  /* we have 3 waitEntry in use
  ** 1, current one: waitEntry-waitOffset-waitEntryAddr-waitEntryIndex
  ** 2, the new one: waitEntryNew-waitOffsetNew-waitEntryAddrNew. The entry to store/insert to waitQ. 
                     Because reading out the LL has 1 cycle latency, so new waitOffset and Addr MAYBE 1 cycle late if checking >1 LL slots
  ** 3, the old one: waitEntryOld-waitOffsetOld. To record the previous LL entry, it is the previous LL entry to connect the new waitEntry
  */ 
  val waitEntry,  waitEntryOld,  waitEntryNew   = Reg(WaitEntryBW(conf)) 
  val waitOffset, waitOffsetOld, waitOffsetNew  = Reg(UInt(conf.wLinkListOffset bits)).init(0)
  val cntCheckEmpty      = Reg(UInt(conf.wLinkListOffset bits)).init(0)
  val cntWaitEntryViewed = Reg(UInt(conf.wLinkListOffset bits)).init(0)
  val waitEntryAddr      = rLockReq.lockID.resize(conf.wLinkList bits) + waitOffset
  val waitEntryAddrNew   = rLockReq.lockID.resize(conf.wLinkList bits) + waitOffsetNew 
  waitEntry := ll(waitEntryAddr)
  val rLockReqIndex  =  rLockReq.srcNode.asBits ##  rLockReq.srcTxnMan.asBits ##  rLockReq.srcTxnIdx.asBits // identify the waitEntry and lockRelease request
  val waitEntryIndex = waitEntry.srcNode.asBits ## waitEntry.srcTxnMan.asBits ## waitEntry.srcTxnIdx.asBits

  val htFSM = new StateMachine {
    val IDLE = new State with EntryPoint
    val WAIT_REQ, HT_READ, LL_READ_ENTRY, LL_FIND_TAIL, LL_FIND_EMPTY, LL_POP, LL_DELETE, LOCK_RESP = new State

    val rRespTypeGrant   = Reg(Bool()).init(False)
    val rRespTypeWait    = Reg(Bool()).init(False)
    val rRespTypeAbort   = Reg(Bool()).init(False)
    val rRespTypeRelease = Reg(Bool()).init(False)
    val rReturnToPop     = Reg(Bool()).init(False)
    val rRespByPop       = Reg(Bool()).init(False)
    val rUpdateHashEntryAddr = Reg(Bool()).init(False)
    val rUpdateWaitEntryOld  = Reg(Bool()).init(False)
    
    // clear Memory data in idle mode
    val zeroHTValue = Reg(HashValueBW(conf))
    val zeroLLValue = Reg(WaitEntryBW(conf))
    zeroHTValue.exclusive  := False
    zeroHTValue.waitQValid := False
    zeroHTValue.ownerCnt   := 0
    zeroHTValue.waitQAddr  := 0
    zeroLLValue.srcNode    := 0
    zeroLLValue.srcTxnMan  := 0
    zeroLLValue.srcTxnIdx  := 0
    zeroLLValue.lockType.read  := False
    zeroLLValue.lockType.write := False
    zeroLLValue.rwLength       := 0
    zeroLLValue.nextReqOffset  := 0
    val hashAddr = Reg(UInt(conf.wHashTable bits)).init(0)
    val linkAddr = Reg(UInt(conf.wLinkList bits)).init(0)
    IDLE.whenIsActive {
      hashAddr := hashAddr + 1
      ht(hashAddr) := zeroHTValue
      linkAddr := linkAddr + 1
      ll(linkAddr) := zeroLLValue
      when(io.start) (goto(WAIT_REQ))
    }
    
    // Wait for lock request
    io.lockRequest.ready := isActive(WAIT_REQ)
    WAIT_REQ.whenIsActive{
      rRespTypeGrant   := False
      rRespTypeWait    := False
      rRespTypeAbort   := False
      rRespTypeRelease := False
      rReturnToPop     := False
      rRespByPop       := False
      cntCheckEmpty    := 0
      cntWaitEntryViewed   := 0
      rUpdateHashEntryAddr := False
      rUpdateWaitEntryOld  := False
      waitOffset    := 0
      waitOffsetOld := 0
      waitOffsetNew := 0
      when(io.lockRequest.fire) {
        hashEntry := ht(io.lockRequest.payload.lockID) // Read the HashTable Value
        goto(HT_READ)
      }
    }

    // Judge based on HashTable Value
    HT_READ.whenIsActive {
      waitEntryNew  := rLockReq.toWaitEntryBW()
      waitOffset    := hashEntry.waitQAddr
      waitOffsetNew := hashEntry.waitQAddr
      switch(rLockReq.toRelease) {
          is(False) { // lock Get
            when(hashEntry.ownerCnt > 0) { // lock exist
              when(hashEntry.exclusive || rLockReq.lockType.write) {  // lock conflict
                when(hashEntry.waitQValid) { // push waitQ -> update waitEntry
                  goto(LL_READ_ENTRY)
                } otherwise { // No WaitQ yet
                  rUpdateHashEntryAddr := True // Need to update the hash entry WaitQ address
                  goto(LL_FIND_EMPTY)
                }
              } otherwise { // shared Lock && lockType = read
                when(hashEntry.ownerCnt.andR){ // ownerCnt will overflow
                  goto(LL_READ_ENTRY)
                } otherwise{  // still have seats...
                  rRespTypeGrant     := True
                  hashEntry.ownerCnt := hashEntry.ownerCnt + 1
                  goto(LOCK_RESP)
                }
              }
            } otherwise { // lock empty
              rRespTypeGrant       := True
              hashEntry.exclusive  := rLockReq.lockType.write
              hashEntry.waitQValid := False
              hashEntry.waitQAddr  := 0
              hashEntry.ownerCnt   := hashEntry.ownerCnt + 1
              goto(LOCK_RESP)
            }
          }

          is(True) { // lock Release
            when(rLockReq.txnTimeOut && hashEntry.waitQValid) { // original lock maybe in WaitQ
                rUpdateHashEntryAddr := True
                goto(LL_DELETE) // if timeOut, a LL traversal of LLDEL first, if del fail -> normal rlse
            } otherwise { // original lock was granted
                when(hashEntry.ownerCnt === 1) { // if only has 1 owner
                  when(hashEntry.waitQValid) { // waitQ exists, needs to go back to pop other Requests
                    rRespTypeRelease := True
                    rReturnToPop     := True
                    hashEntry.ownerCnt := hashEntry.ownerCnt - 1
                    goto(LOCK_RESP)
                  }otherwise{     // no waitQ, just clear and send response
                    rRespTypeRelease := True
                    hashEntry        := zeroHTValue // write zero HT value
                    goto(LOCK_RESP)
                  }
                } otherwise { // if more than 1 owner
                  rRespTypeRelease := True
                  hashEntry.ownerCnt := hashEntry.ownerCnt - 1
                  goto(LOCK_RESP)
                }
            }
          }
      }
    }

    LL_READ_ENTRY.whenIsActive{
      goto(LL_FIND_TAIL)
    }

    // Find the tail of current WaitQ
    LL_FIND_TAIL.whenIsActive {
      when(waitEntry.nextReqOffset > 0){ // continue to go to next waitEntry
        waitOffset := waitOffset + waitEntry.nextReqOffset
        goto(LL_READ_ENTRY)
      } otherwise{ // now the old waitEntry is the tail
        waitEntryOld  := waitEntry
        waitOffsetOld := waitOffset
        waitOffsetNew := waitOffset
        waitOffset    := waitOffset + 1
        goto(LL_FIND_EMPTY)
      }
    }

    // Find an empty waitQ slot
    LL_FIND_EMPTY.whenIsActive {
      when((waitEntryAddr >= conf.maxLinkListAddr) || (cntCheckEmpty > conf.maxCheckEmpty)){ // Address is out of safe boundary, if ll.ins failed (not enough space)
        rRespTypeAbort := True
        // Now add 1 to the ownerCnt, because I will release the aborted lock to reduce the TxnManager complexity
        hashEntry.ownerCnt := hashEntry.ownerCnt + 1
        goto(LOCK_RESP)
      } otherwise { // still within the checking range
        when(waitEntry.lockType.read || waitEntry.lockType.write) { // slot is occupied
          // Go to the next waitEntry
          cntCheckEmpty := cntCheckEmpty + 1
          waitOffsetNew := waitOffsetNew + 1
          waitOffset    := waitOffset + 1
        } otherwise { // find an empty waitQ slot
          rRespTypeWait := True
          when(rUpdateHashEntryAddr) { // If the insert command comes from the HT_READ: no waitQ yet
            hashEntry.waitQAddr := waitOffsetNew
            hashEntry.waitQValid:= True
          } otherwise { // If the insert command comes from the LL_FIND_TAIL: waitQ exists, waitEntryOld is tail
            rUpdateWaitEntryOld := True
            waitEntryOld.nextReqOffset := waitOffsetNew - waitOffsetOld // WaitO & WaitOOld are absolute offset, so nextO is the diff between them
          }
          // Error: The actual waitEntry address is 1 step ahead of the checked entry (1 cycle latency)
          ll(waitEntryAddrNew) := waitEntryNew // Insert the new waitEntry 
          goto(LOCK_RESP)
        }
      }
    }

    LL_DELETE.whenIsActive { // If the waitQ is full, then the offset will be larger and larger... eventually outside of 2^6 bits...and return to 0 after overflow
      when(waitEntryIndex === rLockReqIndex) { // find the req in waitQ (only update waitEntry)
        when(rUpdateHashEntryAddr){        // If this is the first waitQ entry
          hashEntry.waitQAddr := waitOffset
        } otherwise {                      // If this is 2nd/other waitQ entry
          rUpdateWaitEntryOld := True
          waitEntryOld.nextReqOffset := waitEntryOld.nextReqOffset + waitEntry.nextReqOffset // Here we use relative offset
        }
        rRespTypeRelease  := True
        ll(waitEntryAddr) := zeroLLValue // Erase the current waitEntry
        goto(LOCK_RESP)
      } otherwise {  // current waitEntry is not the one
        when(waitEntry.nextReqOffset === 0){ // arrived at the tail = if LLDEL fail, lk has been dequeued, as normal lkRlse
          when(hashEntry.ownerCnt === 1) (rReturnToPop := True) // if only has 1 owner, then need to pop the waitQ
          rRespTypeRelease   := True
          hashEntry.ownerCnt := hashEntry.ownerCnt - 1
          goto(LOCK_RESP)
        } otherwise{ // continue searching
          waitEntryOld := waitEntry
          waitOffset   := waitOffset + waitEntry.nextReqOffset  // absolute offset + relative offset
          rUpdateHashEntryAddr := False
        }
      }
    }

    LL_POP.whenIsActive {
      cntWaitEntryViewed := cntWaitEntryViewed + 1
      val popSuccess = (hashEntry.ownerCnt === 0) || (hashEntry.exclusive === False && waitEntry.lockType.write === False)
      when(popSuccess){ // The current waitEntry is suitable to pop        
        // clear wait Q: popSuccess and only 1 waitEntry, or owerCnt = waitViewed (All viewed waiting locks are popSuccess because all read locks.)
        val popClearQ = ((cntWaitEntryViewed === 0) && (waitEntry.nextReqOffset === 0)) || ((cntWaitEntryViewed === hashEntry.ownerCnt) && (waitEntry.nextReqOffset === 0))
        when(popClearQ)(hashEntry.waitQValid := False)
        // Analysis pop success
        when(hashEntry.ownerCnt === 0){ // arrive at the first waitQ entry: rUpdateHashEntryAddr= True
          rUpdateHashEntryAddr := True  // next waitEntry will become the first waitQ entry, so it should update the address if pop out
          hashEntry.waitQAddr  := waitOffset + waitEntry.nextReqOffset
          hashEntry.exclusive  := waitEntry.lockType.write
        } otherwise{ // or the read lock is compatible, pop the current waitEntry
          when(rUpdateHashEntryAddr){ // still the first waitQ entry, because pop Read Lock Requests consecutively
            hashEntry.waitQAddr := waitOffset + waitEntry.nextReqOffset
          } otherwise{
            rUpdateWaitEntryOld := True
            rUpdateHashEntryAddr := False  
            waitEntryOld.nextReqOffset := waitEntryOld.nextReqOffset + waitEntry.nextReqOffset
          }
        }
        ll(waitEntryAddr)  := zeroLLValue // delete the current waitEntry
        hashEntry.ownerCnt := hashEntry.ownerCnt + 1
        rRespTypeGrant := True
        rRespByPop     := True
        goto(LOCK_RESP)
      } otherwise{ // current waitEntry should not pop
        rUpdateHashEntryAddr := False // we skip one WRITE waitEntry, so no need to update the hashEntry address
        waitEntryOld  := waitEntry    // record the current waitEntry details for next iteration
        waitOffsetOld := waitOffset
        val popContinue = (waitEntry.nextReqOffset > 0) && (hashEntry.exclusive === False)
        when(popContinue) { // continue to go to next waitEntry
          waitOffset := waitOffset + waitEntry.nextReqOffset
        } otherwise { // now arrive at the tail waitEntry OR exclusive lock: go back to WAIT_REQUEST
          goto(WAIT_REQ)
        }
      }
    }

    // Directly assign the lockResponse to reduce the info transfering latency
    io.lockResponse.payload.channelID := io.channelIdx
    io.lockResponse.payload.tableIdx  := 0
    io.lockResponse.payload.lockID    := rLockReq.lockID
    io.lockResponse.payload.granted   := rRespTypeGrant
    io.lockResponse.payload.waiting   := rRespTypeWait
    io.lockResponse.payload.aborted   := rRespTypeAbort
    io.lockResponse.payload.released  := rRespTypeRelease
    when(rRespByPop){
      // io.lockResponse.payload :=  rLockRespFromWait // The lock response Grant/Abort is one cycle late, so the current lock response is not the latest info
      io.lockResponse.payload.srcNode    :=  waitEntry.srcNode
      io.lockResponse.payload.srcTxnMan  :=  waitEntry.srcTxnMan
      io.lockResponse.payload.srcTxnIdx  :=  waitEntry.srcTxnIdx
      io.lockResponse.payload.toRelease  :=  False
      io.lockResponse.payload.txnTimeOut :=  False
      io.lockResponse.payload.lockType   :=  waitEntry.lockType
      io.lockResponse.payload.rwLength   :=  waitEntry.rwLength
    } otherwise {
      // io.lockResponse.payload :=  rLockRespFromReq // The lock response Grant/Abort is one cycle late,
      io.lockResponse.payload.srcNode    :=  rLockReq.srcNode
      io.lockResponse.payload.srcTxnMan  :=  rLockReq.srcTxnMan
      io.lockResponse.payload.srcTxnIdx  :=  rLockReq.srcTxnIdx
      io.lockResponse.payload.toRelease  :=  rLockReq.toRelease
      io.lockResponse.payload.txnTimeOut :=  rLockReq.txnTimeOut
      io.lockResponse.payload.lockType   :=  rLockReq.lockType
      io.lockResponse.payload.rwLength   :=  rLockReq.rwLength
    }
    io.lockResponse.valid := isActive(LOCK_RESP)
    LOCK_RESP.whenIsActive {
      when(io.lockResponse.fire){
        rRespTypeGrant   := False // Clear response types
        rRespTypeWait    := False
        rRespTypeAbort   := False
        rRespTypeRelease := False
        rReturnToPop     := False
        rRespByPop       := False // Clear the response.payload tag
        rUpdateWaitEntryOld  := False
        ht(rLockReq.lockID) := hashEntry // Update the hashEntry details after sending the response
        when(rUpdateWaitEntryOld)(ll(rLockReq.lockID.resize(conf.wLinkList bits) + waitOffsetOld) := waitEntryOld)
        when(rReturnToPop){
          goto(LL_POP)
        } otherwise{
          goto(WAIT_REQ)
        }
      }
    }
  }
}


class LockChannelBW(conf: MinSysConfig) extends Component{
  val io = new LockTableBWIO(conf)
  val tableArray = Array.fill(conf.nTable)(new LockTableBWait(conf))
  tableArray.foreach{ i =>
    i.io.channelIdx := io.channelIdx
    i.io.start      := io.start
  }

  // DeMUX lock requests to multiple Lock Tables
  val lockReq2TableDeMUX = StreamDemux(io.lockRequest, io.lockRequest.payload.tableID, conf.nTable)
  (tableArray, lockReq2TableDeMUX).zipped.foreach(_.io.lockRequest <-/< _) // pipelined and avoid the high fanout

  // Arbitrate the lock responses
  val lockRespArbiter = StreamArbiterFactory.roundRobin.build(LockResponse(conf), conf.nTable)
  (lockRespArbiter.io.inputs, tableArray).zipped.foreach(_ <-/< _.io.lockResponse)
  io.lockResponse << lockRespArbiter.io.output
}