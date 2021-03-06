/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;

/**
 * Plugin to calculate resource information on Linux systems.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class SysInfoLinux extends SysInfo {
  private static final Log LOG =
      LogFactory.getLog(SysInfoLinux.class);

  /**
   * proc's meminfo virtual file has keys-values in the format
   * "key:[ \t]*value[ \t]kB".
   */
  private static final String PROCFS_MEMFILE = "/proc/meminfo";
  private static final Pattern PROCFS_MEMFILE_FORMAT =
      Pattern.compile("^([a-zA-Z]*):[ \t]*([0-9]*)[ \t]kB");

  // We need the values for the following keys in meminfo
  private static final String MEMTOTAL_STRING = "MemTotal";
  private static final String SWAPTOTAL_STRING = "SwapTotal";
  private static final String MEMFREE_STRING = "MemFree";
  private static final String SWAPFREE_STRING = "SwapFree";
  private static final String INACTIVE_STRING = "Inactive";

  /**
   * Patterns for parsing /proc/cpuinfo.
   */
  private static final String PROCFS_CPUINFO = "/proc/cpuinfo";
  private static final Pattern PROCESSOR_FORMAT =
      Pattern.compile("^processor[ \t]:[ \t]*([0-9]*)");
  private static final Pattern FREQUENCY_FORMAT =
      Pattern.compile("^cpu MHz[ \t]*:[ \t]*([0-9.]*)");
  private static final Pattern PHYSICAL_ID_FORMAT =
      Pattern.compile("^physical id[ \t]*:[ \t]*([0-9]*)");
  private static final Pattern CORE_ID_FORMAT =
      Pattern.compile("^core id[ \t]*:[ \t]*([0-9]*)");

  /**
   * Pattern for parsing /proc/stat.
   */
  private static final String PROCFS_STAT = "/proc/stat";
  private static final Pattern CPU_TIME_FORMAT =
      Pattern.compile("^cpu[ \t]*([0-9]*)" +
                      "[ \t]*([0-9]*)[ \t]*([0-9]*)[ \t].*");
  private CpuTimeTracker cpuTimeTracker;

  /**
   * Pattern for parsing /proc/net/dev.
   */
  private static final String PROCFS_NETFILE = "/proc/net/dev";
  private static final Pattern PROCFS_NETFILE_FORMAT =
      Pattern .compile("^[ \t]*([a-zA-Z]+[0-9]*):" +
               "[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)" +
               "[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)" +
               "[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)" +
               "[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+)[ \t]*([0-9]+).*");


  private String procfsMemFile;
  private String procfsCpuFile;
  private String procfsStatFile;
  private String procfsNetFile;
  private long jiffyLengthInMillis;

  private long ramSize = 0;
  private long swapSize = 0;
  private long ramSizeFree = 0;  // free ram space on the machine (kB)
  private long swapSizeFree = 0; // free swap space on the machine (kB)
  private long inactiveSize = 0; // inactive cache memory (kB)
  /* number of logical processors on the system. */
  private int numProcessors = 0;
  /* number of physical cores on the system. */
  private int numCores = 0;
  private long cpuFrequency = 0L; // CPU frequency on the system (kHz)
  private long numNetBytesRead = 0L; // aggregated bytes read from network
  private long numNetBytesWritten = 0L; // aggregated bytes written to network

  private boolean readMemInfoFile = false;
  private boolean readCpuInfoFile = false;

  public static final long PAGE_SIZE = getConf("PAGESIZE");
  public static final long JIFFY_LENGTH_IN_MILLIS =
      Math.max(Math.round(1000D / getConf("CLK_TCK")), -1);

  private static long getConf(String attr) {
    if(Shell.LINUX) {
      try {
        ShellCommandExecutor shellExecutorClk = new ShellCommandExecutor(
            new String[] {"getconf", attr });
        shellExecutorClk.execute();
        return Long.parseLong(shellExecutorClk.getOutput().replace("\n", ""));
      } catch (IOException|NumberFormatException e) {
        return -1;
      }
    }
    return -1;
  }

  /**
   * Get current time.
   * @return Unix time stamp in millisecond
   */
  long getCurrentTime() {
    return System.currentTimeMillis();
  }

  public SysInfoLinux() {
    this(PROCFS_MEMFILE, PROCFS_CPUINFO, PROCFS_STAT,
         PROCFS_NETFILE, JIFFY_LENGTH_IN_MILLIS);
  }

  /**
   * Constructor which allows assigning the /proc/ directories. This will be
   * used only in unit tests.
   * @param procfsMemFile fake file for /proc/meminfo
   * @param procfsCpuFile fake file for /proc/cpuinfo
   * @param procfsStatFile fake file for /proc/stat
   * @param procfsNetFile fake file for /proc/net/dev
   * @param jiffyLengthInMillis fake jiffy length value
   */
  @VisibleForTesting
  public SysInfoLinux(String procfsMemFile,
                                       String procfsCpuFile,
                                       String procfsStatFile,
                                       String procfsNetFile,
                                       long jiffyLengthInMillis) {
    this.procfsMemFile = procfsMemFile;
    this.procfsCpuFile = procfsCpuFile;
    this.procfsStatFile = procfsStatFile;
    this.procfsNetFile = procfsNetFile;
    this.jiffyLengthInMillis = jiffyLengthInMillis;
    this.cpuTimeTracker = new CpuTimeTracker(jiffyLengthInMillis);
  }

  /**
   * Read /proc/meminfo, parse and compute memory information only once.
   */
  private void readProcMemInfoFile() {
    readProcMemInfoFile(false);
  }

  /**
   * Read /proc/meminfo, parse and compute memory information.
   * @param readAgain if false, read only on the first time
   */
  private void readProcMemInfoFile(boolean readAgain) {

    if (readMemInfoFile && !readAgain) {
      return;
    }

    // Read "/proc/memInfo" file
    BufferedReader in;
    InputStreamReader fReader;
    try {
      fReader = new InputStreamReader(
          new FileInputStream(procfsMemFile), Charset.forName("UTF-8"));
      in = new BufferedReader(fReader);
    } catch (FileNotFoundException f) {
      // shouldn't happen....
      LOG.warn("Couldn't read " + procfsMemFile
          + "; can't determine memory settings");
      return;
    }

    Matcher mat;

    try {
      String str = in.readLine();
      while (str != null) {
        mat = PROCFS_MEMFILE_FORMAT.matcher(str);
        if (mat.find()) {
          if (mat.group(1).equals(MEMTOTAL_STRING)) {
            ramSize = Long.parseLong(mat.group(2));
          } else if (mat.group(1).equals(SWAPTOTAL_STRING)) {
            swapSize = Long.parseLong(mat.group(2));
          } else if (mat.group(1).equals(MEMFREE_STRING)) {
            ramSizeFree = Long.parseLong(mat.group(2));
          } else if (mat.group(1).equals(SWAPFREE_STRING)) {
            swapSizeFree = Long.parseLong(mat.group(2));
          } else if (mat.group(1).equals(INACTIVE_STRING)) {
            inactiveSize = Long.parseLong(mat.group(2));
          }
        }
        str = in.readLine();
      }
    } catch (IOException io) {
      LOG.warn("Error reading the stream " + io);
    } finally {
      // Close the streams
      try {
        fReader.close();
        try {
          in.close();
        } catch (IOException i) {
          LOG.warn("Error closing the stream " + in);
        }
      } catch (IOException i) {
        LOG.warn("Error closing the stream " + fReader);
      }
    }

    readMemInfoFile = true;
  }

  /**
   * Read /proc/cpuinfo, parse and calculate CPU information.
   */
  private void readProcCpuInfoFile() {
    // This directory needs to be read only once
    if (readCpuInfoFile) {
      return;
    }
    HashSet<String> coreIdSet = new HashSet<>();
    // Read "/proc/cpuinfo" file
    BufferedReader in;
    InputStreamReader fReader;
    try {
      fReader = new InputStreamReader(
          new FileInputStream(procfsCpuFile), Charset.forName("UTF-8"));
      in = new BufferedReader(fReader);
    } catch (FileNotFoundException f) {
      // shouldn't happen....
      LOG.warn("Couldn't read " + procfsCpuFile + "; can't determine cpu info");
      return;
    }
    Matcher mat;
    try {
      numProcessors = 0;
      numCores = 1;
      String currentPhysicalId = "";
      String str = in.readLine();
      while (str != null) {
        mat = PROCESSOR_FORMAT.matcher(str);
        if (mat.find()) {
          numProcessors++;
        }
        mat = FREQUENCY_FORMAT.matcher(str);
        if (mat.find()) {
          cpuFrequency = (long)(Double.parseDouble(mat.group(1)) * 1000); // kHz
        }
        mat = PHYSICAL_ID_FORMAT.matcher(str);
        if (mat.find()) {
          currentPhysicalId = str;
        }
        mat = CORE_ID_FORMAT.matcher(str);
        if (mat.find()) {
          coreIdSet.add(currentPhysicalId + " " + str);
          numCores = coreIdSet.size();
        }
        str = in.readLine();
      }
    } catch (IOException io) {
      LOG.warn("Error reading the stream " + io);
    } finally {
      // Close the streams
      try {
        fReader.close();
        try {
          in.close();
        } catch (IOException i) {
          LOG.warn("Error closing the stream " + in);
        }
      } catch (IOException i) {
        LOG.warn("Error closing the stream " + fReader);
      }
    }
    readCpuInfoFile = true;
  }

  /**
   * Read /proc/stat file, parse and calculate cumulative CPU.
   */
  private void readProcStatFile() {
    // Read "/proc/stat" file
    BufferedReader in;
    InputStreamReader fReader;
    try {
      fReader = new InputStreamReader(
          new FileInputStream(procfsStatFile), Charset.forName("UTF-8"));
      in = new BufferedReader(fReader);
    } catch (FileNotFoundException f) {
      // shouldn't happen....
      return;
    }

    Matcher mat;
    try {
      String str = in.readLine();
      while (str != null) {
        mat = CPU_TIME_FORMAT.matcher(str);
        if (mat.find()) {
          long uTime = Long.parseLong(mat.group(1));
          long nTime = Long.parseLong(mat.group(2));
          long sTime = Long.parseLong(mat.group(3));
          cpuTimeTracker.updateElapsedJiffies(
              BigInteger.valueOf(uTime + nTime + sTime),
              getCurrentTime());
          break;
        }
        str = in.readLine();
      }
    } catch (IOException io) {
      LOG.warn("Error reading the stream " + io);
    } finally {
      // Close the streams
      try {
        fReader.close();
        try {
          in.close();
        } catch (IOException i) {
          LOG.warn("Error closing the stream " + in);
        }
      } catch (IOException i) {
        LOG.warn("Error closing the stream " + fReader);
      }
    }
  }

  /**
   * Read /proc/net/dev file, parse and calculate amount
   * of bytes read and written through the network.
   */
  private void readProcNetInfoFile() {

    numNetBytesRead = 0L;
    numNetBytesWritten = 0L;

    // Read "/proc/net/dev" file
    BufferedReader in;
    InputStreamReader fReader;
    try {
      fReader = new InputStreamReader(
          new FileInputStream(procfsNetFile), Charset.forName("UTF-8"));
      in = new BufferedReader(fReader);
    } catch (FileNotFoundException f) {
      return;
    }

    Matcher mat;
    try {
      String str = in.readLine();
      while (str != null) {
        mat = PROCFS_NETFILE_FORMAT.matcher(str);
        if (mat.find()) {
          assert mat.groupCount() >= 16;

          // ignore loopback interfaces
          if (mat.group(1).equals("lo")) {
            str = in.readLine();
            continue;
          }
          numNetBytesRead += Long.parseLong(mat.group(2));
          numNetBytesWritten += Long.parseLong(mat.group(10));
        }
        str = in.readLine();
      }
    } catch (IOException io) {
      LOG.warn("Error reading the stream " + io);
    } finally {
      // Close the streams
      try {
        fReader.close();
        try {
          in.close();
        } catch (IOException i) {
          LOG.warn("Error closing the stream " + in);
        }
      } catch (IOException i) {
        LOG.warn("Error closing the stream " + fReader);
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public long getPhysicalMemorySize() {
    readProcMemInfoFile();
    return ramSize * 1024;
  }

  /** {@inheritDoc} */
  @Override
  public long getVirtualMemorySize() {
    readProcMemInfoFile();
    return (ramSize + swapSize) * 1024;
  }

  /** {@inheritDoc} */
  @Override
  public long getAvailablePhysicalMemorySize() {
    readProcMemInfoFile(true);
    return (ramSizeFree + inactiveSize) * 1024;
  }

  /** {@inheritDoc} */
  @Override
  public long getAvailableVirtualMemorySize() {
    readProcMemInfoFile(true);
    return (ramSizeFree + swapSizeFree + inactiveSize) * 1024;
  }

  /** {@inheritDoc} */
  @Override
  public int getNumProcessors() {
    readProcCpuInfoFile();
    return numProcessors;
  }

  /** {@inheritDoc} */
  @Override
  public int getNumCores() {
    readProcCpuInfoFile();
    return numCores;
  }

  /** {@inheritDoc} */
  @Override
  public long getCpuFrequency() {
    readProcCpuInfoFile();
    return cpuFrequency;
  }

  /** {@inheritDoc} */
  @Override
  public long getCumulativeCpuTime() {
    readProcStatFile();
    return cpuTimeTracker.getCumulativeCpuTime();
  }

  /** {@inheritDoc} */
  @Override
  public float getCpuUsage() {
    readProcStatFile();
    float overallCpuUsage = cpuTimeTracker.getCpuTrackerUsagePercent();
    if (overallCpuUsage != CpuTimeTracker.UNAVAILABLE) {
      overallCpuUsage = overallCpuUsage / getNumProcessors();
    }
    return overallCpuUsage;
  }

  /** {@inheritDoc} */
  @Override
  public long getNetworkBytesRead() {
    readProcNetInfoFile();
    return numNetBytesRead;
  }

  /** {@inheritDoc} */
  @Override
  public long getNetworkBytesWritten() {
    readProcNetInfoFile();
    return numNetBytesWritten;
  }

  /**
   * Test the {@link SysInfoLinux}.
   *
   * @param args - arguments to this calculator test
   */
  public static void main(String[] args) {
    SysInfoLinux plugin = new SysInfoLinux();
    System.out.println("Physical memory Size (bytes) : "
        + plugin.getPhysicalMemorySize());
    System.out.println("Total Virtual memory Size (bytes) : "
        + plugin.getVirtualMemorySize());
    System.out.println("Available Physical memory Size (bytes) : "
        + plugin.getAvailablePhysicalMemorySize());
    System.out.println("Total Available Virtual memory Size (bytes) : "
        + plugin.getAvailableVirtualMemorySize());
    System.out.println("Number of Processors : " + plugin.getNumProcessors());
    System.out.println("CPU frequency (kHz) : " + plugin.getCpuFrequency());
    System.out.println("Cumulative CPU time (ms) : " +
            plugin.getCumulativeCpuTime());
    System.out.println("Total network read (bytes) : "
            + plugin.getNetworkBytesRead());
    System.out.println("Total network written (bytes) : "
            + plugin.getNetworkBytesWritten());
    try {
      // Sleep so we can compute the CPU usage
      Thread.sleep(500L);
    } catch (InterruptedException e) {
      // do nothing
    }
    System.out.println("CPU usage % : " + plugin.getCpuUsage());
  }

  @VisibleForTesting
  void setReadCpuInfoFile(boolean readCpuInfoFileValue) {
    this.readCpuInfoFile = readCpuInfoFileValue;
  }

  public long getJiffyLengthInMillis() {
    return this.jiffyLengthInMillis;
  }
}
