// Copy the source code for quick reference. From https://github.com/SpinalHDL/SpinalHDL/blob/master/lib/src/main/scala/spinal/lib/Stream.scala

/**
 *  A StreamArbiter is like a StreamMux, but with built-in complex selection logic that can arbitrate input
 *  streams based on a schedule or handle fragmented streams. Use a StreamArbiterFactory to create instances of this class.
 
class StreamArbiter[T <: Data](dataType: HardType[T], val portCount: Int)(val arbitrationFactory: (StreamArbiter[T]) => Area, val lockFactory: (StreamArbiter[T]) => Area) extends Component {
  val io = new Bundle {
    val inputs = Vec(slave Stream (dataType),portCount)
    val output = master Stream (dataType)
    val chosen = out UInt (log2Up(portCount) bit)
    val chosenOH = out Bits (portCount bit)
  }

  val locked = RegInit(False).allowUnsetRegToAvoidLatch

  val maskProposal = Vec(Bool(),portCount)
  val maskLocked = Reg(Vec(Bool(),portCount))
  val maskRouted = Mux(locked, maskLocked, maskProposal)


  when(io.output.valid) {
    maskLocked := maskRouted
  }

  val arbitration = arbitrationFactory(this)
  val lock = lockFactory(this)

  io.output.valid := (io.inputs, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
  io.output.payload := MuxOH(maskRouted,Vec(io.inputs.map(_.payload)))
  (io.inputs, maskRouted).zipped.foreach(_.ready := _ & io.output.ready)

  io.chosenOH := maskRouted.asBits
  io.chosen := OHToUInt(io.chosenOH)
}

class StreamArbiterFactory {
  var arbitrationLogic: (StreamArbiter[_ <: Data]) => Area = StreamArbiter.Arbitration.lowerFirst
  var lockLogic: (StreamArbiter[_ <: Data]) => Area = StreamArbiter.Lock.transactionLock

  def build[T <: Data](dataType: HardType[T], portCount: Int): StreamArbiter[T] = {
    new StreamArbiter(dataType, portCount)(arbitrationLogic, lockLogic)
  }

  def buildOn[T <: Data](inputs : Seq[Stream[T]]): StreamArbiter[T] = {
    val a = new StreamArbiter(inputs.head.payloadType, inputs.size)(arbitrationLogic, lockLogic)
    (a.io.inputs, inputs).zipped.foreach(_ << _)
    a
  }

  def buildOn[T <: Data](first : Stream[T], others : Stream[T]*): StreamArbiter[T] = {
    buildOn(first :: others.toList)
  }

  def onArgs[T <: Data](inputs: Stream[T]*): Stream[T] = on(inputs.seq)
  def on[T <: Data](inputs: Seq[Stream[T]]): Stream[T] = {
    val arbiter = build(inputs(0).payloadType, inputs.size)
    (arbiter.io.inputs, inputs).zipped.foreach(_ << _)
    val ret = arbiter.io.output.combStage()
    //    arbiter.setCompositeName(ret, "arbiter")
    ret
  }

  def lowerFirst: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.lowerFirst
    this
  }
  def roundRobin: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.roundRobin
    this
  }
  def sequentialOrder: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.sequentialOrder
    this
  }

  def setLock(body : (StreamArbiter[_ <: Data]) => Area) : this.type = {
    lockLogic = body
    this
  }
  def noLock: this.type = setLock(StreamArbiter.Lock.none)
  def fragmentLock: this.type = setLock(StreamArbiter.Lock.fragmentLock)
  def transactionLock: this.type = setLock(StreamArbiter.Lock.transactionLock)
  def lambdaLock[T <: Data](unlock: Stream[T] => Bool) : this.type = setLock{
    case c : StreamArbiter[T] => new Area {
      import c._
      locked setWhen(io.output.valid)
      locked.clearWhen(io.output.fire && unlock(io.output))
    }
  }
}

class StreamMux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val inputs = Vec(slave Stream (dataType), portCount)
    val output = master Stream (dataType)
    def createStreamRegSelect(): Stream[UInt] = new Composite(this, "selector") {
      val stream = Stream(cloneOf(select))
      val reg = stream.haltWhen(output.isStall).toReg(U(0))
      select := reg
    }.stream
  }
  for ((input, index) <- io.inputs.zipWithIndex) {
    input.ready := io.select === index && io.output.ready
  }
  io.output.valid := io.inputs(io.select).valid
  io.output.payload := io.inputs(io.select).payload
}

class StreamDemux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType),portCount)
    def createStreamRegSelect(): Stream[UInt] = new Composite(this, "selector") {
      val stream = Stream(cloneOf(select))
      val reg = stream.haltWhen(input.isStall).toReg(U(0))
      select := reg
    }.stream
  }
  io.input.ready := False
  for (i <- 0 to portCount - 1) {
    io.outputs(i).payload := io.input.payload
    when(i =/= io.select) {
      io.outputs(i).valid := False
    } otherwise {
      io.outputs(i).valid := io.input.valid
      io.input.ready := io.outputs(i).ready
    }
  }
}

    def roundRobin(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      for(bitId  <- maskLocked.range){
        maskLocked(bitId) init(Bool(bitId == maskLocked.length-1))
      }
      //maskProposal := maskLocked
      maskProposal := OHMasking.roundRobin(Vec(io.inputs.map(_.valid)),Vec(maskLocked.last +: maskLocked.take(maskLocked.length-1)))
    }
*/
