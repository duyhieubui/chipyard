package chipyard.fpga.arty_a7_100

import chisel3._
import chisel3.experimental.{IO, Analog}

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.jtag._
import freechips.rocketchip.devices.debug._

import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.shell._
import sifive.blocks.devices.uart._
import sifive.fpgashells.clocks._
import sifive.fpgashells.ip.xilinx._

import chipyard.{HasHarnessSignalReferences, HasTestHarnessFunctions, BuildTop, ChipTop, ExtTLMem, CanHaveMasterTLMemPort, DefaultClockFrequencyKey, HasReferenceClockFreq}
import chipyard.iobinders.{HasIOBinders}
import chipyard.harness.{ApplyHarnessBinders}

class Arty100TFPGATestHarness(override implicit val p: Parameters) extends Arty100TShellBasicOverlays {
  def dp = designParameters

  val topDesign = LazyModule(p(BuildTop)(dp)).suggestName("chiptop")
  val pllReset = InModuleBody { Wire(Bool()) }

  require(dp(ClockInputOverlayKey).size >= 1)

  val sysClkNode = dp(ClockInputOverlayKey)(0).place(ClockInputDesignInput()).overlayOutput.node

  // connect to the PLL that will generate multiple clocks
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  println(s"Arty A7 100T FPGA Base Clock Freq: ${dp(DefaultClockFrequencyKey)} MHz")
  val dutClock = ClockSinkNode(freqMHz = dp(DefaultClockFrequencyKey))

  val dutWrangler = LazyModule(new ResetWrangler)

  val dutGroup = ClockGroup()

  dutClock := dutWrangler.node := dutGroup := harnessSysPLL

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  dp(UARTOverlayKey).head.place(UARTDesignInput(io_uart_bb))
  println(s"DP: ${dp}")

  val ddrNode = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLL)).overlayOutput.ddr

  // connect 1 mem. channel to the fpga ddr
  val inParams = topDesign match {case td: ChipTop =>
    td.lazySystem match { case lsys: CanHaveMasterTLMemPort =>
      lsys.memTLNode.edges.in(0)
    }
  }
  val ddrClient = TLClientNode (Seq(inParams.master))
  ddrNode := ddrClient
  override lazy val module = new Arty100TFPGATestHarnessImp(this)
  
}

class Arty100TFPGATestHarnessImp (_outer: Arty100TFPGATestHarness) extends LazyRawModuleImp(_outer) with HasHarnessSignalReferences {
  val artyOuter = _outer

  val reset = IO(Input(Bool()))
  _outer.xdc.addPackagePin(reset, "C2")
  _outer.xdc.addIOStandard(reset, "LVCMOS33")

  val resetIBUF = Module(new IBUF)
  resetIBUF.io.I := reset
  val ndreset    = Wire(Bool())


  // jtag interface
  // JD (used for JTAG connection)
  val jd_0         = IO(Analog(1.W))  // TDO
  val jd_1         = IO(Analog(1.W))  // TRST_n
  val jd_2         = IO(Analog(1.W))  // TCK
  val jd_4         = IO(Analog(1.W))  // TDI
  val jd_5         = IO(Analog(1.W))  // TMS
  val jd_6         = IO(Analog(1.W))  // SRST_n

  val SRST_n         = Wire(Bool())

  val sysclk: Clock = _outer.sysClkNode.out.head._1.clock
  val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
  _outer.sdc.addAsyncPath(Seq(powerOnReset))

  _outer.pllReset := (!resetIBUF.io.O || powerOnReset || ndreset)

  // reset setup
  val hReset = Wire(Reset())
  hReset := _outer.dutClock.in.head._1.reset

  val buildtopClock = _outer.dutClock.in.head._1.clock
  val buildtopReset = WireInit(hReset)
  val dutReset = hReset.asAsyncReset
  val success = false.B

  childClock := buildtopClock
  childReset := buildtopReset

  _outer.topDesign match { case d: HasTestHarnessFunctions =>
    d.harnessFunctions.foreach(_(this))
  }
  _outer.topDesign match { case d: HasIOBinders =>
    ApplyHarnessBinders(this, d.lazySystem, d.portMap)
  }
}


