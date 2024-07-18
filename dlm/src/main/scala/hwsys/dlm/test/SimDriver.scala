package hwsys.dlm.test

import spinal.core._
import spinal.lib.bus.amba4.axi._
import hwsys.util._
import hwsys.sim._
import hwsys.dlm._


case class TxnEntrySim(
                        nId: Int,
                        cId: Int,
                        tId: Int,
                        tabId: Int,
                        lkType: Int,
                        wLen: Int
                      )(implicit sysConf: SysConfig) extends MemStructSim {
  override def asBytes = SimConversions.txnEntrySimToBytes(this)
}


// Conversion methods
object SimConversions {

  def txnEntrySimToBytes(req: TxnEntrySim)(implicit sysConf: SysConfig) : Seq[Byte] = {
    val vBigInt = req.nId +
      (req.cId << (sysConf.shiftChannel)) +
      (req.tId << (sysConf.shiftTID)) +
      (req.tabId << (sysConf.shiftTable)) +
      (req.lkType << (sysConf.shiftLkType)) +
      (req.wLen << (sysConf.shiftWriteLen))

//    println(s"txnBigInt=${vBigInt.toHexString}")

    MemStructSim.bigIntToBytes(vBigInt, 8)
  }

}

case class LockEntrySim(nID: Int, cID: Int, tID: Int, lockID: Int, lockType: Int, rwLen: Int)(implicit conf: MinSysConfig){
  def toBytes(byteLen: Int): Seq[Byte] = {
    val v = BigInt(this.nID + this.cID << conf.sChannel + this.tID << conf.sTable + this.lockID << conf.sLockID + this.lockType << conf.sLockType + this.rwLen << conf.sRWLength)
    v.toByteArray.reverse.padTo(byteLen, 0.toByte)
  }
  def bigIntToBytes(v: BigInt, byteLen: Int) : Seq[Byte] = {
    v.toByteArray.reverse.padTo(byteLen, 0.toByte)
  }
}

// Init methods
object SimInit {
    def txnEntrySim(txnCnt: Int, txnLen: Int, txnMaxLen: Int, tIdOffs: Int = 0)(fNId: (Int, Int) => Int, fCId: (Int, Int) => Int, fTId: (Int, Int) => Int, fLk: (Int, Int) => Int, fLockType: (Int, Int) => Int, fWLen: (Int, Int) => Int)(implicit sysConf: MinSysConfig): Seq[Byte] = {
      var txnMem = Seq.empty[Byte]
      for (i <- 0 until txnCnt) {
        // txnHead
        txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(txnLen), 4)
        // println(MemStructSim.bigIntToBytes(BigInt(txnLen), 4)) // (32, 0, 0, 0)
        for (j <- 0 until txnLen) { // Scala << expression needs (x << (shift_size)) to ensure correctness!!!
          var aLock = fNId(i, j) + (fCId(i, j) << (sysConf.sChannel)) + ((fTId(i, j) + tIdOffs) << (sysConf.sTable)) + (fLk(i, j) << (sysConf.sLockID)) + (fLockType(i, j) << (sysConf.sLockType)) + (fWLen(i, j) << (sysConf.sRWLength))
          // println(aLock, "fNID", fNId(i,j), sysConf.sNodeID, "fCID",fCId(i, j), sysConf.sChannel, "fTID", (fTId(i, j) + tIdOffs), sysConf.sTable , "fLock", fLk(i, j), sysConf.sLockID, "fLockType", fLockType(i, j), sysConf.sLockType, "fWLen", fWLen(i, j), sysConf.sRWLength)
          txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(aLock), 4) // LockType=3, read and write
        }
        for (j <- 0 until (txnMaxLen - txnLen - 1))
          txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(0), 4)
      }
      txnMem
    }

  def txnEntrySimInt(txnCnt: Int, txnLen: Int, txnMaxLen: Int, tIdOffs: Int = 0)(fNId: (Int, Int) => Int, fCId: (Int, Int) => Int, fTId: (Int, Int) => Int, fLk: (Int, Int) => Int, fWLen: (Int, Int) => Int)(implicit sysConf: SysConfig): Seq[Byte] = {
    var txnMem = Seq.empty[Byte]
    for (i <- 0 until txnCnt) {
      // txnHd
      txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(txnLen), 4)
      // lkInsTab
      // txnMem = txnMem ++ TxnEntrySim(fNId(i, 0), fCId(i, 0), fTId(i, 0) + tIdOffs, 0, 3, fWLen(i, 0)).asBytes
      for (j <- 0 until txnLen) {
        txnMem = txnMem ++ TxnEntrySim(fNId(i, j), fCId(i, j), fTId(i, j) + tIdOffs, 0, fLk(i, j), fWLen(i, j)).asBytes
      }
      for (j <- 0 until (txnMaxLen-txnLen))
        txnMem = txnMem ++ MemStructSim.bigIntToBytes(BigInt(0), 4)
    }
    txnMem
  }
}

