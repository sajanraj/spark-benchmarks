/*
 * Copyright 2017 BBVA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bbva.spark.benchmarks.alluxio

import org.json4s.BuildInfo
import scopt.OptionParser

sealed trait TestMode {
  def command: String
}

case object Write extends TestMode { def command: String = "write" }
case object Read extends TestMode { def command: String = "read" }
case object Clean extends TestMode { def command: String = "clean" }
// TODO complete Append and Truncate
case object Append extends TestMode { def command: String = "append" }
case object Truncate extends TestMode { def command: String = "truncate" }
case object NotDefined extends TestMode { def command: String = "not-defined" }

case class TestAlluxioIOConf(mode: TestMode = NotDefined,
                             numFiles: Int = 4,
                             fileSize: Long = 128,
                             benchmarkDir: String = "/benchmarks/TestAlluxioIO",
                             readBehavior: String = "CACHE_PROMOTE",
                             writeBehavior: String = "MUST_CACHE",
                             compression: Option[String] = None)

// TODO include alluxio hostname

object TestAlluxioIOConfParser {

  private lazy val parser = new OptionParser[TestAlluxioIOConf]("TestAlluxioIO") {

    head(s"Test Alluxio I/O ${BuildInfo.version}")

    cmd("write").text(
      """Runs a test writing to the cluster. The written files are located in Alluxio under the folder
        |defined by the option <outputDir>. If the folder already exists, it will be first deleted.
      """.stripMargin)
      .action((_, c) => c.copy(mode = Write))
      .children(

        opt[Int]("numFiles").required().valueName("<value>")
          .action((n, c) => c.copy(numFiles = n))
          .text("Number of files to write. Default to 4."),

        opt[String]("fileSize").required().valueName("<value>")
          .action((s, c) => c.copy(fileSize = sizeToBytes(s)))
          .validate(validateSize)
          .text("Size of each file to write (B|KB|MB|GB). Default to 128B."),

        opt[String]("outputDir").required().valueName("<file>")
          .action((o, c) => c.copy(benchmarkDir = o))
          .text("Name of the directory to place the resultant files. Default to /benchmarks/TestAlluxioIO"),

        opt[String]("writeBehavior").optional().valueName("<behavior>")
          .action((b, c) => c.copy(writeBehavior = b))
          .validate(x =>
            if (List("MUST_CACHE", "CACHE_THROUGH", "THROUGH", "ASYNC_THROUGH").contains(x)) success
            else failure("Behavior must be one of: MUST_CACHE|CACHE_THROUGH|THROUGH|ASYNC_THROUGH")
          )
          .text("The data write behavior when writing a new file (MUST_CACHE|CACHE_THROUGH|THROUGH|ASYNC_THROUGH). Default to MUST_CACHE"),

        opt[String]("compression").optional().valueName("<codec>")
          .action((x, c) => c.copy(compression = Some(x)))
          .validate(x =>
            if (List("lz4", "snappy", "gzip", "bzip2").contains(x)) success
            else failure("Compression codec must be one of: lz4|snappy|gzip|bzip2")
          )
          .text("The compression codec to use (lz4|snappy|gzip|bzip2)")

      )

    cmd("read").text(
      """Runs a test reading from the cluster. It is convenient to run test with command write first, so that some
        |files are prepared for read test. If the test is run with this command before it is run with command write,
        |an error message will be shown up.
      """.stripMargin)
      .action((_, c) => c.copy(mode = Read))
      .children(

        opt[Int]("numFiles").required().valueName("<value>")
          .action((n, c) => c.copy(numFiles = n))
          .text("Number of files to read. Default to 4."),

        opt[String]("fileSize").required().valueName("<value>")
          .validate(validateSize)
          .action((s, c) => c.copy(fileSize = sizeToBytes(s)))
          .text("Size of each file to read (B|KB|MB|GB). Default to 128B."),

        opt[String]("inputDir").required().valueName("<file>")
          .action((o, c) => c.copy(benchmarkDir = o))
          .text("Name of the directory where to find the files to read. Default to /benchmarks/TestAlluxioIO"),

        opt[String]("readBehavior").optional().valueName("<behavior>")
          .action((b, c) => c.copy(readBehavior = b))
          .validate(x =>
            if (List("CACHE_PROMOTE", "CACHE", "NO_CACHE").contains(x)) success
            else failure("Behavior must be one of: CACHE_PROMOTE|CACHE|NO_CACHE")
          )
          .text("The data read behavior when reading a new file (CACHE_PROMOTE|CACHE|NO_CACHE). Default to CACHE_PROMOTE"),

        opt[String]("compression").optional().valueName("<codec>")
          .action((x, c) => c.copy(compression = Some(x)))
          .validate(x =>
            if (List("lz4", "snappy", "gzip", "bzip2").contains(x)) success
            else failure("Compression codec must be one of: lz4|snappy|gzip|bzip2")
          )
          .text("The compression codec to use (lz4|snappy|gzip|bzip2)")
    )

    cmd("clean").text("Remove previous test data. This command deletes de output directory.")
      .action((_, c) => c.copy(mode = Clean))
      .children(
        opt[String]("outputDir").required().valueName("<file>")
          .action((o, c) => c.copy(benchmarkDir = o))
          .text("Name of the directory to clean. Default to /benchmarks/TestAlluxioIO")
      )

    checkConfig { conf =>
      if (conf.mode != NotDefined) success else failure("A command is required.")
    }

    help("help").text("prints this usage text")

    version("version")

    private val SizePattern = "^(\\d+(?:\\.\\d+)?)(([kKmMgG]?[bB]))$".r

    private def validateSize(size: String): Either[String, Unit] = {
      if (size.matches(SizePattern.toString)) success
      else failure("The size must be valid")
    }

    private def sizeToBytes(size: String): Long = {
      val units = List("b", "kb", "mb", "gb")
      val matcher = SizePattern.findFirstMatchIn(size).get
      val value = matcher.group(1)
      val unit = matcher.group(2).toLowerCase
      (value.toFloat * math.pow(2, units.indexOf(unit) * 10)).toLong
    }

  }

  def parseAndRun(args: Seq[String])(runFunc: TestAlluxioIOConf => Unit): Unit =
    parser.parse(args, TestAlluxioIOConf()) match {
      case Some(conf) => runFunc(conf)
      case None => // ignore
    }

}
