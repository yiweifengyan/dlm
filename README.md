# Hardware Transaction Processing for Multi-channel Memory Node

This version [Upgrade SpinalHDL 1.7.3](https://github.com/rbshi/dlm/commit/57af3afd098f05efb3974a9ed5d25fca57713327) - works well in WrapNodeNetSim & SysCoyote1T2N1C8PGen

The memory node in cloud computing usually has parallel memory channels and memory interfaces with unified data access. Building a management layer on top of the parallel interfaces is not trivial to ensure data integrity (e.g., atomicity). The concept of transaction origins from the database domain has been applied as a general memory semantic. However, software-based transaction processing suffers from a high cost of concurrency control (e.g., lock management). Building a hardware transaction processing layer for the multi-channel memory node is valuable. Our target is to run the hardware as a background daemon and use the expensive memory bandwidth for data access in transactions. The project provides transaction processing hardware with three concurrency control schemes (No wait 2PL, Bounded wait 2PL, and Timestamp OCC). It has been prototyped with [Coyote (an FPGA OS)](https://github.com/fpgasystems/Coyote) on [Alveo FPGA cluster with HBM](https://xilinx.github.io/xacc/ethz.html). The transaction tasks are injected via either TCP or PCIe from the clients.

```
.
├── src/main/             # source files
│   ├── lib/              # external lib
│   │   ├── HashTable.    # 
│   │   └── LinkedList.   # 
│   └── scala/            # design with SpinalHDL
│       └── hwsys/        # hwsys lib
│           ├── coyote    # interface and datatype to coyote
│           ├── dlm/      # distributed lock manager
│           │   └── test  # testbench of dlm
│           ├── sim       # helper function for testbench
│           └── util      # hardware utilities
├── build.sbt             # sbt project
└── build.sc              # mill project
```

## Install Software

- [JDK 8 LTS](https://adoptium.net/zh-CN/temurin/releases/?version=8)  sudo yum install java-1.8.0-openjdk-devel     sudo yum install java-1.8.0-openjdk
- [Scala 2.12.14](https://www.scala-lang.org/download/2.12.14.html)  sudo yum install ./Downloads/scala-2.12.14.rpm
- [mill 0.10.4](https://github.com/com-lihaoyi/mill/releases/tag/0.10.4)  
- [SpinalHDL Install Verilator Guide](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Simulation/install/Verilator.html)
- [GTK Wave](https://sourceforge.net/projects/gtkwave/)

On Red Hat 9:

- [tcl](https://centos.pkgs.org/7/centos-x86_64/tcl-8.5.13-8.el7.x86_64.rpm.html) sudo yum install ./tcl-8.5.13-8.el7.x86_64.rpm 
- [tk](https://centos.pkgs.org/7/centos-x86_64/tk-8.5.13-6.el7.x86_64.rpm.html) sudo yum install ./tk-8.5.13-6.el7.x86_64.rpm 
- gtkwave:  sudo yum install gtkwave

On Windows

- [MSYS2](https://www.msys2.org/) if using Windows
- [Verilator](https://www.veripool.org/verilator/) in [MSYS2 distribution](https://packages.msys2.org/base/mingw-w64-verilator). Select the version consistent with the MYSYS2 CLI Window. I used UCRT64 in my case.

Config enviroment variables:

- VERILATOR_ROOT: C:\msys64\ucrt64\share\verilator  +  C:\msys64\ucrt64
- PATH: + C:\msys64\ucrt64\bin  +  C:\msys64\usr\bin 
- JAVA_HOME: auto configed by java installation in winsods. export JAVA_HOME="/usr/lib/jvm/java"

## Scripts

Hardware generation: `SysCoyote1T2N1C8PGen` is an example system config name defined in [Generator.scala](https://github.com/rbshi/dlm/blob/master/src/main/scala/hwsys/dlm/Generator.scala)
```
$ mill dlm.runMain hwsys.dlm.SysCoyote1T2N1C8PGen
./mill-0.10.4 dlm.runMain hwsys.dlm.SysCoyote1T2N1C8PGen
.\mill-0.10.3-assembly.bat dlm.runMain hwsys.dlm.SysCoyote1T2N1C8PGen
```

Simulation: `WrapNodeNetSim` is a testbench defined in [WrapNodeNetSim.scala](https://github.com/rbshi/dlm/blob/master/src/main/scala/hwsys/dlm/test/WrapNodeNetSim.scala)
```
$ mill dlm.runMain hwsys.dlm.test.WrapNodeNetSim
./mill-0.10.4 dlm.runMain hwsys.dlm.test.WrapNodeNetSim
.\mill-0.10.3-assembly.bat dlm.runMain hwsys.dlm.test.WrapNodeNetSim
On Linux Mint:
./mill-0.10.4 dlm.runMain hwsys.dlm.test.TableSim
./mill-0.10.4 dlm.runMain hwsys.dlm.test.CoreSim
```

## Linux Mint / Ubuntu / Debian Setup
 - install JDK, Scala, and SpinalHDL using cs
 - change the scala version into 2.12.14 and uninstall other scala 3.x libs
 - install verilator<5.0 and gtkwave using apt, don't use oss-cad-suite
```
sudo apt-get update
sudo apt-get install openjdk-17-jdk-headless curl git
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
chmod +x cs
# should find the just installed jdk, agree to cs' questions for adding to your PATH
./cs setup
source ~/.profile
# check cs libraries 
cs list
java --version
scala -version
cs Install application commands:
  install    Install an application from its descriptor.
  list       List all currently installed applications.
  setup      Setup a machine for Scala development.
  uninstall  Uninstall one or more applications.
  update     Update one or more applications.
``` 

Error:  
%Error: Cannot find verilated_std.sv containing built-in std:: definitions: /ucrt64/share/verilator\include\verilated_std.sv  
%Error: This may be because there's no search path specified with -I<dir>.  

Solution:  
C:\msys64\ucrt64\share\verilator\include\verilated_std.sv  
verilator -Wall --cc <your_verilog_file> +incdir+<path_to_verilated_std.sv>  
+incdir+"C:\msys64\ucrt64\share\verilator\include\verilated_std.sv"  
+incdir+"C:/msys64/ucrt64/share/verilator/include/verilated_std.sv"  

 .\simWorkspace\TwoNodeNetTop\verilatorScript.sh  
[15412:0111/102004.500:ERROR:cache_util_win.cc(20)] Unable to move the cache: ????? (0x5)  
[15412:0111/102004.500:ERROR:disk_cache.cc(205)] Unable to create cache  

Error:  
Can't locate FindBin.pm in @INC (you may need to install the FindBin module) (@INC contains: /usr/local/lib64/perl5/5.32 /usr/local/share/perl5/5.32 /usr/lib64/perl5/vendor_perl /usr/share/perl5/vendor_perl /usr/lib64/perl5 /usr/share/perl5) at /usr/bin/verilator line 28.  
BEGIN failed--compilation aborted at /usr/bin/verilator line 28.  

Solution:  
sudo yum install perl  

Error:  
fatal error: jni.h: No such file or directory     5 | #include <jni.h>  

Solution:  
export JAVA_HOME="/usr/lib/jvm/java"  

Error:
error opening scalactic x.x.x...

Solution:
https://get-coursier.io/docs/cli-fetch the scalactic package and paste the module dependency info into build.sc 

LOG: TableSim works fine with 128 txns with 30 read requests.
shaun@shaun-virtual-machine:~/Documents/dlm$ ./mill-0.10.4 dlm.runMain hwsys.dlm.test.TableSim
[31/42] dlm.compile 
[info] compiling 1 Scala source to /home/shaun/Documents/dlm/out/dlm/compile.dest/classes ...
[info] done compiling
[42/42] dlm.runMain 
[Runtime] SpinalHDL v1.7.3    git head : aeaeece704fe43c766e0d36a93f2ecbb8a9f2003
[Runtime] JVM max memory : 2478.0MiB
[Runtime] Current date : 2024.07.19 16:31:58
[Progress] at 0.000 : Elaborate components
[Progress] at 0.843 : Checks and transforms
[Progress] at 1.453 : Generate Verilog
[Warning] toplevel/table_1/ht : Mem[65536*16 bits].readAsync can only be write first into Verilog
[Warning] toplevel/table_1/ll : Mem[8192*24 bits].readAsync can only be write first into Verilog
[Warning] 297 signals were pruned. You can call printPruned on the backend report to get more informations.
[Done] at 1.883
[Progress] Simulation workspace in /home/shaun/Documents/dlm/./simWorkspace/OneTxnManOneLockTable
[Progress] Verilator compilation started
[Progress] Verilator compilation done in 4892.287 ms
[Progress] Start OneTxnManOneLockTable OneTxnManOneLockTable simulation with seed 99
(Txn Context Length: ,16384)
Adding page 0 at 0x0
Adding page 0 at 0x0
Adding page 1 at 0x100000
Adding page 2 at 0x200000
Adding page 3 at 0x300000
Adding page 4 at 0x400000
Adding page 5 at 0x500000
Adding page 6 at 0x600000
Adding page 7 at 0x700000
Adding page 8 at 0x800000
Adding page 9 at 0x900000
Adding page 10 at 0xa00000
Adding page 11 at 0xb00000
Adding page 12 at 0xc00000
Adding page 13 at 0xd00000
Adding page 14 at 0xe00000
Adding page 15 at 0xf00000
Adding page 16 at 0x1000000
Adding page 17 at 0x1100000
Adding page 18 at 0x1200000
Adding page 19 at 0x1300000
Adding page 20 at 0x1400000
Adding page 21 at 0x1500000
Adding page 22 at 0x1600000
Adding page 23 at 0x1700000
Adding page 24 at 0x1800000
Adding page 25 at 0x1900000
Adding page 26 at 0x1a00000
Adding page 27 at 0x1b00000
Adding page 28 at 0x1c00000
Adding page 29 at 0x1d00000
Adding page 30 at 0x1e00000
Adding page 31 at 0x1f00000
Adding page 32 at 0x2000000
Adding page 33 at 0x2100000
Adding page 34 at 0x2200000
Adding page 35 at 0x2300000
Adding page 36 at 0x2400000
Adding page 37 at 0x2500000
Adding page 38 at 0x2600000
Adding page 39 at 0x2700000
Adding page 40 at 0x2800000
Adding page 41 at 0x2900000
Adding page 42 at 0x2a00000
Adding page 43 at 0x2b00000
Adding page 44 at 0x2c00000
Adding page 45 at 0x2d00000
Adding page 46 at 0x2e00000
Adding page 47 at 0x2f00000
Adding page 48 at 0x3000000
Adding page 49 at 0x3100000
Adding page 50 at 0x3200000
Adding page 51 at 0x3300000
Adding page 52 at 0x3400000
Adding page 53 at 0x3500000
Adding page 54 at 0x3600000
Adding page 55 at 0x3700000
Adding page 56 at 0x3800000
Adding page 57 at 0x3900000
Adding page 58 at 0x3a00000
Adding page 59 at 0x3b00000
Adding page 60 at 0x3c00000
Adding page 61 at 0x3d00000
Adding page 62 at 0x3e00000
Adding page 63 at 0x3f00000
Handling AXI4 Master read cmds...
Handling AXI4 Master read resp...
Handling AXI4 Master write cmds...
Handling AXI4 Master write...
Handling AXI4 Master read cmds...
Handling AXI4 Master read resp...
Handling AXI4 Master write cmds...
Handling AXI4 Master write...
[txnMan] cntTxnCmt: 128
[txnMan] cntTxnAbt: 0
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 38468
[Done] Simulation done in 12497.288 ms

shaun@shaun-virtual-machine:~/Documents/dlm$ ./mill-0.10.4 dlm.runMain hwsys.dlm.test.TableSim
[42/42] dlm.runMain 
[Runtime] SpinalHDL v1.7.3    git head : aeaeece704fe43c766e0d36a93f2ecbb8a9f2003
[Runtime] JVM max memory : 2478.0MiB
[Runtime] Current date : 2024.07.31 15:16:23
[Progress] at 0.000 : Elaborate components
[Progress] at 0.982 : Checks and transforms
[Progress] at 1.835 : Generate Verilog
[Warning] toplevel/table_1/ht : Mem[65536*16 bits].readAsync can only be write first into Verilog
[Warning] toplevel/table_1/ll : Mem[8192*24 bits].readAsync can only be write first into Verilog
[Warning] 297 signals were pruned. You can call printPruned on the backend report to get more informations.
[Done] at 2.455
[Progress] Simulation workspace in /home/shaun/Documents/dlm/./simWorkspace/OneTxnManOneLockTable
[Progress] Verilator compilation started
[info] Found cached verilator binaries
[Progress] Verilator compilation done in 4538.930 ms
[Progress] Start OneTxnManOneLockTable OneTxnManOneLockTable simulation with seed 99
(Txn Context Length: ,16384)
[txnMan] cntTxnCmt: 128
[txnMan] cntTxnAbt: 0
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 133480
[Done] Simulation done in 22555.227 ms

With optimized waitEntryAddrFast:
[Runtime] SpinalHDL v1.7.3    git head : aeaeece704fe43c766e0d36a93f2ecbb8a9f2003
[Runtime] JVM max memory : 2478.0MiB
[Runtime] Current date : 2024.08.02 18:36:34
[txnMan] cntTxnCmt: 128
[txnMan] cntTxnAbt: 0
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 89026
[Done] Simulation done in 34009.162 ms

Dense write, 128 txns with 30 sequential lockIDs. 
[txnMan] cntTxnCmt: 12
[txnMan] cntTxnAbt: 116
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 7039
[Done] Simulation done in 24560.012 ms

Two TxnMan Two Tables simulate passed all read. Node 0 and 1 both 128 txns,
[txnMan] cntTxnCmt: 128
[txnMan] cntTxnAbt: 0
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 41080
[Done] Simulation done in 28181.376 ms

Two TxnMan Two Tables passed Read-Write mixed simulation. Node 0 128 txns, node 1 16 txns.
shaun@shaun-virtual-machine:~/Documents/dlm$ ./mill-0.10.4 dlm.runMain hwsys.dlm.test.CoreSim
[31/42] dlm.compile 
[info] compiling 1 Scala source to /home/shaun/Documents/dlm/out/dlm/compile.dest/classes ...
[info] done compiling
[42/42] dlm.runMain 
[Runtime] SpinalHDL v1.7.3    git head : aeaeece704fe43c766e0d36a93f2ecbb8a9f2003
[Runtime] JVM max memory : 2478.0MiB
[Runtime] Current date : 2024.08.07 11:54:29
[Progress] at 0.000 : Elaborate components
[Progress] at 1.088 : Checks and transforms
[Progress] at 2.191 : Generate Verilog
[txnMan] cntTxnCmt: 128
[txnMan] cntTxnAbt: 0
[txnMan] cntTxnLd: 128
[txnMan] cntClk: 32433
[Done] Simulation done in 20409.428 ms