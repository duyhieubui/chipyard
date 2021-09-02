package chipyard.fpga.arty_a7_100

import chisel3._
import chisel3.experimental.{BaseModule}

import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.jtag.{JTAGIO}
import sifive.blocks.devices.uart._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.jtag._
import sifive.blocks.devices.pinctrl._
import sifive.fpgashells.ip.xilinx.{IBUFG, IOBUF, PULLUP, PowerOnResetFPGAOnly}

import chipyard.harness.{ComposeHarnessBinder, OverrideHarnessBinder}
import chipyard.{HasHarnessSignalReferences, CanHaveMasterTLMemPort}
import chipyard.iobinders.{JTAGChipIO}

class WithArtyResetHarnessBinder extends ComposeHarnessBinder({
  (system: HasPeripheryDebugModuleImp, th: Arty100TFPGATestHarnessImp, ports: Seq[Bool]) => {
    require(ports.size == 2)

    withClockAndReset(th.buildtopClock, th.dutReset) {
      // Debug module reset
      th.ndreset := ports(0)

      // JTAG reset
      ports(1) := th.powerOnReset
    }
  }
})

class WithUART extends OverrideHarnessBinder({
  (system: HasPeripheryUARTModuleImp, th: BaseModule with HasHarnessSignalReferences, ports: Seq[UARTPortIO]) => {
    th match { case artyth: Arty100TFPGATestHarnessImp => {
      artyth.artyOuter.io_uart_bb.bundle <> ports.head
    } }
  }
})

class WithDDRMem extends OverrideHarnessBinder ({
  (system: CanHaveMasterTLMemPort, th: BaseModule with HasHarnessSignalReferences, ports: Seq[HeterogeneousBag[TLBundle]]) => {
    th match { case artyth: Arty100TFPGATestHarnessImp => {
      require(ports.size == 1)
      val bundles = artyth.artyOuter.ddrClient.out.map(_._1)
      val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
      bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
      ddrClientBundle <> ports.head
    } }
  }
})
class WithArty100TJTAGHarnessBinder extends OverrideHarnessBinder({
  (system: HasPeripheryDebug, th: Arty100TFPGATestHarnessImp, ports: Seq[Data]) => {
    ports.map {
      case j: JTAGChipIO =>
        withClockAndReset(th.buildtopClock, th.hReset) {
          val jtag_wire = Wire(new JTAGIO)
          jtag_wire.TDO.data := j.TDO
          jtag_wire.TDO.driven := true.B
          j.TCK := jtag_wire.TCK
          j.TMS := jtag_wire.TMS
          j.TDI := jtag_wire.TDI

          val io_jtag = Wire(new JTAGPins(() => new BasePin(), false)).suggestName("jtag")

          JTAGPinsFromPort(io_jtag, jtag_wire)

          io_jtag.TCK.i.ival := IBUFG(IOBUF(th.jd_2).asClock).asBool

          IOBUF(th.jd_5, io_jtag.TMS)
          PULLUP(th.jd_5)

          IOBUF(th.jd_4, io_jtag.TDI)
          PULLUP(th.jd_4)

          IOBUF(th.jd_0, io_jtag.TDO)

          // mimic putting a pullup on this line (part of reset vote)
          th.SRST_n := IOBUF(th.jd_6)
          PULLUP(th.jd_6)

          //ignore the po input
          io_jtag.TCK.i.po.map(_ := DontCare)
          io_jtag.TDI.i.po.map(_ := DontCare)
          io_jtag.TMS.i.po.map(_ := DontCare)
          io_jtag.TDO.i.po.map(_ := DontCare)
        }
    }
  }
})
