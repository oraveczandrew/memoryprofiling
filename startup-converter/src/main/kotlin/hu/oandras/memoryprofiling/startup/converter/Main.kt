/*
 *    Copyright 2026 András Oravecz <info@oandras.hu>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package hu.oandras.memoryprofiling.startup.converter

import hu.oandras.memoryprofiling.internal.AsdbRows
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private const val ROWS_PER_COMMIT = 5_000

private data class Options(
    @JvmField val input: String,
    @JvmField val output: File,
    @JvmField val doneMarker: File?,
    @JvmField val force: Boolean,
    @JvmField val pidOverride: Int?,
)

fun main(args: Array<String>) {
    if (args.size == 1 && (args[0] == "-h" || args[0] == "--help")) {
        println(USAGE)
        return
    }
    val options = try {
        parseArguments(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("startup-converter: ${e.message}")
        System.err.println(USAGE)
        exitProcess(2)
    }

    try {
        convert(options)
    } catch (e: StartupRawException) {
        System.err.println("startup-converter: ${e.message}")
        exitProcess(1)
    } catch (e: IOException) {
        System.err.println("startup-converter: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("startup-converter: ${e.message ?: e::class.java.name}")
        e.printStackTrace()
        exitProcess(1)
    }
}

internal data class ConversionSummary(
    @JvmField val batches: Long,
    @JvmField val allocations: Long,
    @JvmField val dropped: Long,
    @JvmField val processId: Int,
    @JvmField val ended: Boolean,
    @JvmField val hasDoneMarker: Boolean,
)

private fun convert(options: Options) {
    val input = if (options.input == "-") {
        null
    } else {
        val file = File(options.input)
        if (!file.isFile) {
            throw IOException("Input file does not exist: $file.")
        }
        file
    }
    val doneMarker = options.doneMarker
        ?: if (input != null) File(input.path + ".done") else null
    val summary = convertCapture(
        input = input,
        output = options.output,
        doneMarker = doneMarker,
        force = options.force,
        pidOverride = options.pidOverride,
    )

    if (!summary.ended) {
        System.err.println(
            "startup-converter: warning: the capture has no end marker; it may be incomplete.",
        )
    }
    if (!summary.hasDoneMarker) {
        System.err.println(
            "startup-converter: warning: no .done marker found; the dropped count may be low.",
        )
    }
    println(
        "Converted ${summary.batches} batch(es) and ${summary.allocations} allocation(s) " +
            "from pid ${summary.processId} (${summary.dropped} dropped) into ${options.output}.",
    )
}

internal fun convertCapture(
    input: File?,
    output: File,
    doneMarker: File?,
    force: Boolean,
    pidOverride: Int?,
): ConversionSummary {
    if (output.exists() && !force) {
        throw IOException("Output already exists: $output. Pass --force to overwrite it.")
    }
    output.parentFile?.mkdirs()
    val temporary = File(output.parentFile, "${output.name}.tmp")
    temporary.delete()
    File(temporary.path + "-journal").delete()
    temporary.deleteOnExit()

    val stream = if (input == null) {
        DataInputStream(System.`in`.buffered())
    } else {
        DataInputStream(input.inputStream().buffered())
    }

    val reader = StartupRawReader(stream)
    val header = reader.readHeader()
    val done = doneMarker?.let(StartupRawReader::readDoneMarker)
    val processId = pidOverride ?: header.processId
    if (processId <= 0) {
        throw IOException("The raw capture has no process id. Pass --pid.")
    }

    var batches = 0L
    var allocations = 0L
    var dropped = done?.dropped ?: 0L
    val rows = AsdbRows(contextTimestampNs = header.startTimeNs)
    val database = AsdbDatabaseWriter(temporary)
    var pendingRows = 0

    try {
        database.use { writer ->
            while (true) {
                val batch = reader.nextBatch() ?: break
                batches++
                allocations += batch.allocations.size
                if (batch.dropped > dropped) {
                    dropped = batch.dropped
                }
                for (row in rows.consume(batch)) {
                    writer.insert(processId, row)
                    pendingRows++
                    if (pendingRows >= ROWS_PER_COMMIT) {
                        writer.flush()
                        pendingRows = 0
                    }
                }
            }
            rows.finish()?.let { row ->
                writer.insert(processId, row)
                pendingRows++
            }
            if (pendingRows > 0) {
                writer.flush()
            }
        }
    } catch (e: Exception) {
        temporary.delete()
        File(temporary.path + "-journal").delete()
        throw e
    }

    try {
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    return ConversionSummary(
        batches = batches,
        allocations = allocations,
        dropped = dropped,
        processId = processId,
        ended = reader.ended,
        hasDoneMarker = done != null,
    )
}

private fun parseArguments(args: Array<String>): Options {
    var force = false
    var pid: Int? = null
    var doneMarker: File? = null
    val positional = ArrayList<String>(2)

    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--force" -> force = true
            "--pid" -> {
                index++
                if (index >= args.size) {
                    throw IllegalArgumentException("--pid needs a value.")
                }
                pid = args[index].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid process id: ${args[index]}.")
            }
            "--done" -> {
                index++
                if (index >= args.size) {
                    throw IllegalArgumentException("--done needs a path.")
                }
                doneMarker = File(args[index])
            }
            "-h", "--help" -> throw IllegalArgumentException("Help requested.")
            else -> {
                if (argument.startsWith("--")) {
                    throw IllegalArgumentException("Unknown option: $argument.")
                }
                positional += argument
            }
        }
        index++
    }

    if (positional.isEmpty() || positional.size > 2) {
        throw IllegalArgumentException("Expected an input file and an optional output file.")
    }
    val input = positional[0]
    val output = when {
        positional.size == 2 -> File(positional[1])
        input == "-" -> throw IllegalArgumentException("An output file is required for stdin input.")
        else -> {
            val withoutExtension = input.substringBeforeLast('.', input)
            File("$withoutExtension.asdb")
        }
    }
    return Options(
        input = input,
        output = output,
        doneMarker = doneMarker,
        force = force,
        pidOverride = pid,
    )
}

private const val USAGE = """
Usage: startup-converter [options] <capture.raw|-> [capture.asdb]

Options:
  --force         Overwrite an existing output file.
  --pid <pid>     Process id to store when the raw header has none.
  --done <file>   Read start/end/dropped values from this .done marker.
  -h, --help      Show this help.
"""
