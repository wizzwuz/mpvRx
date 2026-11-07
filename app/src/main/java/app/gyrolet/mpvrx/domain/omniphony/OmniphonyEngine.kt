/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.omniphony

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.OmniphonyHeadphoneRendering
import app.gyrolet.mpvrx.preferences.OmniphonyOutputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The Omniphony engine (liborender) and decoder bridges that libmpv's `ad_orender` decoder loads.
 *
 * They are user-supplied native libraries: imported into app storage, checked against the ABI of
 * the running libmpv, and handed to the player through a generated `config.yaml`.
 */
class OmniphonyEngine(
  private val context: Context,
  private val preferences: AudioPreferences,
) {
  data class InstalledFile(
    val name: String,
    val sizeBytes: Long,
  )

  data class Installation(
    val engine: InstalledFile? = null,
    val bridges: List<InstalledFile> = emptyList(),
    val hrtf: InstalledFile? = null,
    val brir: InstalledFile? = null,
  )

  /** Options for a player core that renders through Omniphony. */
  data class Setup(
    val libraryPath: String,
    val configPath: String,
    val showOverlay: Boolean,
    val configurationKey: String,
  )

  enum class SofaKind(
    val directory: String,
  ) {
    Hrtf("hrtf"),
    Brir("brir"),
  }

  enum class ImportProblem {
    WrongArchitecture,
    NotALibrary,
    UnknownLibrary,
    TooLarge,
    Unreadable,
  }

  data class ImportResult(
    val engineImported: Boolean = false,
    val bridgesImported: Int = 0,
    val problems: Set<ImportProblem> = emptySet(),
  ) {
    val imported: Boolean
      get() = engineImported || bridgesImported > 0

    internal fun withProblem(problem: ImportProblem) = copy(problems = problems + problem)
  }

  private enum class LibraryOutcome { Engine, Bridge, WrongArchitecture, NotALibrary, Unknown, TooLarge }

  private class ElfHeader(
    val type: Int,
    val machine: Int,
  )

  private val root = File(context.filesDir, "omniphony")
  private val engineFile = File(root, "lib/$ENGINE_NAME")
  private val bridgeDir = File(root, "plugins")
  private val configFile = File(root, "config.yaml")
  private val _installation = MutableStateFlow(Installation())
  val installation: StateFlow<Installation> = _installation.asStateFlow()

  private val expectedMachine: Int? by lazy {
    readElfHeader(File(context.applicationInfo.nativeLibraryDir, "libmpv.so"))?.machine
      ?: machineForAbi(fallbackAbi())
  }

  /** The Android ABI that imported libraries must be built for. */
  val deviceAbi: String by lazy { expectedMachine?.let(::abiForMachine) ?: fallbackAbi() }

  fun refresh() {
    _installation.value = scan()
  }

  /**
   * Writes the engine configuration for the next player core. Returns null while the engine is
   * switched off or not installed, so libmpv keeps its own decoders.
   */
  fun prepare(): Setup? {
    val installation = scan().also { _installation.value = it }
    if (!preferences.omniphonyEnabled.get() || installation.engine == null) return null
    return runCatching {
      val config = buildConfig(installation)
      writeAtomically(configFile, config)
      // A live-state handoff left by an earlier engine would override these settings.
      File(root, "config.live.yaml").delete()
      if (Os.getenv(CONFIG_DIR_ENV) != root.path) Os.setenv(CONFIG_DIR_ENV, root.path, true)
      val showOverlay = preferences.omniphonyOverlay.get()
      Setup(
        libraryPath = engineFile.path,
        configPath = configFile.path,
        showOverlay = showOverlay,
        configurationKey = "${engineFile.lastModified()}-${config.hashCode()}-$showOverlay",
      )
    }.onFailure { Log.w(TAG, "Unable to prepare the Omniphony engine", it) }.getOrNull()
  }

  /** Imports liborender.so and `*_bridge.so` files, directly or from .zip archives. */
  fun importLibraries(uris: List<Uri>): ImportResult {
    var result = ImportResult()
    for (uri in uris) {
      val name = displayName(uri)
      result =
        runCatching {
          context.contentResolver.openInputStream(uri)?.use { input ->
            val stream = input.buffered()
            if (startsWithZipMagic(stream)) importArchive(stream, result) else importSingle(name, stream, result)
          } ?: result.withProblem(ImportProblem.Unreadable)
        }.getOrElse { error ->
          Log.w(TAG, "Unable to import $name", error)
          result.withProblem(ImportProblem.Unreadable)
        }
    }
    refresh()
    return result
  }

  /** Imports a SOFA set (personal HRTF or measured room) as the single file of its kind. */
  fun importSofa(
    kind: SofaKind,
    uri: Uri,
  ): Boolean {
    val directory = sofaDirectory(kind)
    val imported =
      runCatching {
        val name = sanitize(displayName(uri)).let { if (it.endsWith(SOFA_SUFFIX, ignoreCase = true)) it else "$it$SOFA_SUFFIX" }
        val staged =
          context.contentResolver.openInputStream(uri)?.use { stage(it, directory, MAX_SOFA_BYTES) }
            ?: return@runCatching false
        try {
          if (!isHdf5(staged)) return@runCatching false
          staged.setWritable(false, false)
          sofaFiles(kind).forEach(File::delete)
          Files.move(staged.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
          true
        } finally {
          staged.delete()
        }
      }.onFailure { Log.w(TAG, "Unable to import a SOFA file", it) }.getOrDefault(false)
    refresh()
    return imported
  }

  fun removeEngine() {
    engineFile.delete()
    refresh()
  }

  fun removeBridge(name: String) {
    File(bridgeDir, File(name).name).delete()
    refresh()
  }

  fun removeSofa(kind: SofaKind) {
    sofaFiles(kind).forEach(File::delete)
    refresh()
  }

  fun removeAll() {
    root.deleteRecursively()
    refresh()
  }

  private fun scan(): Installation =
    Installation(
      engine = engineFile.takeIf(File::isFile)?.toInstalled(),
      bridges = bridgeFiles().map { it.toInstalled() },
      hrtf = sofaFiles(SofaKind.Hrtf).firstOrNull()?.toInstalled(),
      brir = sofaFiles(SofaKind.Brir).firstOrNull()?.toInstalled(),
    )

  private fun File.toInstalled() = InstalledFile(name, length())

  private fun bridgeFiles(): List<File> =
    bridgeDir.listFiles { file -> file.isFile && file.name.endsWith(BRIDGE_SUFFIX) }.orEmpty().sortedBy { it.name }

  private fun sofaDirectory(kind: SofaKind) = File(root, "sofa/${kind.directory}")

  private fun sofaFiles(kind: SofaKind): List<File> =
    sofaDirectory(kind)
      .listFiles { file -> file.isFile && file.name.endsWith(SOFA_SUFFIX, ignoreCase = true) }
      .orEmpty()
      .sortedByDescending { it.lastModified() }

  private fun importArchive(
    input: InputStream,
    start: ImportResult,
  ): ImportResult {
    var result = start
    var importedHere = false
    var skippedForArchitecture = false
    ZipInputStream(input).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        // Only the base name is used, so archive paths cannot escape the plugin folders.
        val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
        if (entry.isDirectory || !name.endsWith(".so", ignoreCase = true)) continue
        when (importLibrary(name, zip)) {
          LibraryOutcome.Engine -> {
            result = result.copy(engineImported = true)
            importedHere = true
          }
          LibraryOutcome.Bridge -> {
            result = result.copy(bridgesImported = result.bridgesImported + 1)
            importedHere = true
          }
          LibraryOutcome.WrongArchitecture -> skippedForArchitecture = true
          LibraryOutcome.TooLarge -> result = result.withProblem(ImportProblem.TooLarge)
          LibraryOutcome.NotALibrary, LibraryOutcome.Unknown -> Unit
        }
      }
    }
    return when {
      importedHere -> result
      skippedForArchitecture -> result.withProblem(ImportProblem.WrongArchitecture)
      else -> result.withProblem(ImportProblem.UnknownLibrary)
    }
  }

  private fun importSingle(
    name: String,
    input: InputStream,
    result: ImportResult,
  ): ImportResult =
    when (importLibrary(name, input)) {
      LibraryOutcome.Engine -> result.copy(engineImported = true)
      LibraryOutcome.Bridge -> result.copy(bridgesImported = result.bridgesImported + 1)
      LibraryOutcome.WrongArchitecture -> result.withProblem(ImportProblem.WrongArchitecture)
      LibraryOutcome.NotALibrary -> result.withProblem(ImportProblem.NotALibrary)
      LibraryOutcome.Unknown -> result.withProblem(ImportProblem.UnknownLibrary)
      LibraryOutcome.TooLarge -> result.withProblem(ImportProblem.TooLarge)
    }

  private fun importLibrary(
    name: String,
    input: InputStream,
  ): LibraryOutcome {
    val lowerName = name.lowercase(Locale.ROOT)
    val target =
      when {
        lowerName.endsWith(BRIDGE_SUFFIX) -> File(bridgeDir, sanitize(name))
        lowerName.startsWith("liborender") && lowerName.endsWith(".so") -> engineFile
        else -> return LibraryOutcome.Unknown
      }
    val directory = target.parentFile ?: return LibraryOutcome.Unknown
    val staged = stage(input, directory, MAX_LIBRARY_BYTES) ?: return LibraryOutcome.TooLarge
    try {
      val elf = readElfHeader(staged)
      if (elf == null || elf.type != ELF_TYPE_SHARED) return LibraryOutcome.NotALibrary
      if (expectedMachine != null && elf.machine != expectedMachine) return LibraryOutcome.WrongArchitecture
      // A loaded copy stays mapped: replace the file by rename, never rewrite it in place.
      staged.setReadable(true, true)
      staged.setExecutable(true, true)
      staged.setWritable(false, false)
      Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
      staged.delete()
    }
    return if (target == engineFile) LibraryOutcome.Engine else LibraryOutcome.Bridge
  }

  /** Copies [input] into a temporary file of [directory], or returns null past [limit] bytes. */
  private fun stage(
    input: InputStream,
    directory: File,
    limit: Long,
  ): File? {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
    val staged = File.createTempFile(".import", ".tmp", directory)
    try {
      var copied = 0L
      staged.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          copied += count
          if (copied > limit) {
            staged.delete()
            return null
          }
          output.write(buffer, 0, count)
        }
      }
      return staged
    } catch (error: IOException) {
      staged.delete()
      throw error
    }
  }

  private fun buildConfig(installation: Installation): String =
    buildString {
      appendLine("# Written by mpvRx before the player starts; edits here are replaced.")
      appendLine("render:")
      appendLine("  osc: false")
      appendLine("  output_channel_mapping: by_name")
      appendLine("  channel_render_mode: ${if (preferences.omniphonySpatializeChannels.get()) "spatial" else "host"}")
      if (installation.bridges.isNotEmpty()) {
        appendLine("  bridge_paths:")
        installation.bridges.forEach { appendLine("    - ${yamlString(File(bridgeDir, it.name).path)}") }
      }
      val speakers = preferences.omniphonyOutputMode.get() == OmniphonyOutputMode.Speakers
      if (speakers) {
        appendLine("  current_layout:")
        appendLine("    radius_m: 1")
        appendLine("    speakers:")
        preferences.omniphonySpeakerLayout.get().speakers.forEach { speaker ->
          appendLine("      - name: ${yamlString(speaker.name)}")
          appendLine("        coord_mode: cartesian")
          appendLine("        x: ${speaker.x}")
          appendLine("        y: ${speaker.y}")
          appendLine("        z: ${speaker.z}")
          appendLine("        spatialize: ${speaker.spatialize}")
        }
      }
      appendLine("  binaural:")
      appendLine("    output_mode: ${if (speakers) "speaker" else "binaural"}")
      if (!speakers) {
        val rendering = preferences.omniphonyHeadphoneRendering.get()
        val hrtf = installation.hrtf
        val brir = installation.brir
        when {
          rendering == OmniphonyHeadphoneRendering.PersonalHrtf && hrtf != null -> {
            appendLine("    hrir_source: sofa")
            appendLine("    hrtf_sofa_path: ${yamlString(File(sofaDirectory(SofaKind.Hrtf), hrtf.name).path)}")
          }
          rendering == OmniphonyHeadphoneRendering.MeasuredRoom && brir != null -> {
            appendLine("    hrir_source: brir")
            appendLine("    brir_sofa_path: ${yamlString(File(sofaDirectory(SofaKind.Brir), brir.name).path)}")
          }
          else -> appendLine("    hrir_source: saf")
        }
        val room = preferences.omniphonyRoomSimulation.get()
        appendLine("    reflections:")
        appendLine("      enabled: $room")
        appendLine("    reverb:")
        appendLine("      enabled: $room")
      }
    }

  private fun writeAtomically(
    target: File,
    content: String,
  ) {
    val directory = target.parentFile ?: throw IOException("No directory for $target")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
    val staged = File.createTempFile(".config", ".tmp", directory)
    try {
      staged.writeText(content)
      Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
      staged.delete()
    }
  }

  private fun displayName(uri: Uri): String =
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }
    }.getOrNull()
      ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: "plugin"

  private fun startsWithZipMagic(stream: InputStream): Boolean {
    stream.mark(ZIP_MAGIC.size)
    val head = ByteArray(ZIP_MAGIC.size)
    val read = readFully(stream, head)
    stream.reset()
    return read == head.size && head.contentEquals(ZIP_MAGIC)
  }

  private fun readElfHeader(file: File): ElfHeader? {
    if (!file.isFile) return null
    val header = ByteArray(ELF_HEADER_SIZE)
    val read = runCatching { file.inputStream().use { readFully(it, header) } }.getOrDefault(0)
    if (read < ELF_HEADER_SIZE || !header.copyOf(ELF_MAGIC.size).contentEquals(ELF_MAGIC) ||
      header[ELF_DATA_OFFSET] != ELF_LITTLE_ENDIAN
    ) return null
    return ElfHeader(type = header.u16(ELF_TYPE_OFFSET), machine = header.u16(ELF_MACHINE_OFFSET))
  }

  private fun isHdf5(file: File): Boolean {
    val head = ByteArray(HDF5_SEARCH_BYTES)
    val read = runCatching { file.inputStream().use { readFully(it, head) } }.getOrDefault(0)
    // HDF5 allows a user block, so the signature may start at 0, 512, 1024 or 2048.
    return HDF5_OFFSETS.any { offset ->
      offset + HDF5_MAGIC.size <= read && head.copyOfRange(offset, offset + HDF5_MAGIC.size).contentEquals(HDF5_MAGIC)
    }
  }

  private fun readFully(
    stream: InputStream,
    buffer: ByteArray,
  ): Int {
    var total = 0
    while (total < buffer.size) {
      val count = stream.read(buffer, total, buffer.size - total)
      if (count < 0) break
      total += count
    }
    return total
  }

  private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

  private fun fallbackAbi(): String {
    val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
    return abis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
  }

  private fun machineForAbi(abi: String): Int? =
    when (abi) {
      "arm64-v8a" -> EM_AARCH64
      "armeabi-v7a" -> EM_ARM
      "x86_64" -> EM_X86_64
      "x86" -> EM_386
      else -> null
    }

  private fun abiForMachine(machine: Int): String? =
    when (machine) {
      EM_AARCH64 -> "arm64-v8a"
      EM_ARM -> "armeabi-v7a"
      EM_X86_64 -> "x86_64"
      EM_386 -> "x86"
      else -> null
    }

  private fun sanitize(name: String): String = name.replace(UNSAFE_NAME_CHARACTERS, "_").ifEmpty { "plugin" }

  private fun yamlString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private companion object {
    const val TAG = "OmniphonyEngine"
    const val ENGINE_NAME = "liborender.so"
    const val BRIDGE_SUFFIX = "_bridge.so"
    const val SOFA_SUFFIX = ".sofa"
    const val CONFIG_DIR_ENV = "OMNIPHONY_CONFIG_DIR"
    const val MAX_LIBRARY_BYTES = 256L * 1024 * 1024
    const val MAX_SOFA_BYTES = 1024L * 1024 * 1024
    const val ELF_HEADER_SIZE = 20
    const val ELF_DATA_OFFSET = 5
    const val ELF_TYPE_OFFSET = 16
    const val ELF_MACHINE_OFFSET = 18
    const val ELF_LITTLE_ENDIAN: Byte = 1
    const val ELF_TYPE_SHARED = 3
    const val EM_386 = 3
    const val EM_ARM = 40
    const val EM_X86_64 = 62
    const val EM_AARCH64 = 183
    const val HDF5_SEARCH_BYTES = 2056
    val HDF5_OFFSETS = intArrayOf(0, 512, 1024, 2048)
    val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    val HDF5_MAGIC = byteArrayOf(0x89.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0x1a, '\n'.code.toByte())
    val UNSAFE_NAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")
  }
}
