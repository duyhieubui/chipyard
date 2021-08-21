package chipyard.fpga.arty_a7_100

import chisel3._
import chisel3.experimental.{BaseModule}

import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.devices.debug._
import sifive.blocks.devices.uart._
import sifive.blocks.devices.spi._

import chipyard.harness.{ComposeHarnessBinder, OverrideHarnessBinder}
import chipyard.{HasHarnessSignalReferences, CanHaveMasterTLMemPort}
import chipyard.iobinders.{JTAGChipIO}


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
