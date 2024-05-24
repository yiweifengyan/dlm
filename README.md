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
