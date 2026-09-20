import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip

abstract class CheckFoundationPack : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val sourceRoot = sourceDirectory.get().asFile.toPath()
        val sourceFiles =
            sourceRoot.toFile().walkTopDown().filter(File::isFile).associateBy { file ->
                sourceRoot.relativize(file.toPath()).toString().replace('\\', '/')
            }
        val requiredFiles =
            setOf(
                "README.md",
                "NOTICE.md",
                "shaders/lib/licenses/OpenDRT-GPL-3.0.txt",
                "shaders/lib/licenses/Khronos-ToneMapping.txt",
            )
        check(sourceFiles.keys.containsAll(requiredFiles)) {
            "Foundation pack is missing required files: ${requiredFiles - sourceFiles.keys}"
        }
        val unexpectedFiles =
            sourceFiles.keys.filterNot { path ->
                path == "README.md" || path == "NOTICE.md" || path.startsWith("shaders/")
            }
        check(unexpectedFiles.isEmpty()) {
            "Foundation pack files must be rooted at README.md, NOTICE.md, or shaders/: $unexpectedFiles"
        }

        ZipFile(archiveFile.get().asFile).use { archive ->
            val archiveFiles = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
            val entryNames = archiveFiles.map { it.name }
            check(entryNames.size == entryNames.toSet().size) {
                "Foundation pack ZIP contains duplicate file entries"
            }
            val archiveEntries = archiveFiles.associateBy { it.name }
            check(archiveEntries.keys == sourceFiles.keys) {
                "Foundation pack ZIP layout differs from src/pack: " +
                    "missing=${sourceFiles.keys - archiveEntries.keys}, " +
                    "extra=${archiveEntries.keys - sourceFiles.keys}"
            }
            sourceFiles.forEach { (path, sourceFile) ->
                check(
                    sha256(sourceFile.inputStream()) ==
                        sha256(archive.getInputStream(archiveEntries.getValue(path))),
                ) {
                    "Foundation pack ZIP content differs from src/pack/$path"
                }
            }
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}

val packDirectory = layout.projectDirectory.dir("src/pack")

val packageFoundationPack =
    tasks.register<Zip>("packageFoundationPack") {
        from(packDirectory)
        archiveFileName.set("Vulkanite-Foundation.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val checkFoundationPack =
    tasks.register<CheckFoundationPack>("checkFoundationPack") {
        sourceDirectory.set(packDirectory)
        archiveFile.set(packageFoundationPack.flatMap { it.archiveFile })
    }

tasks.named("check") {
    dependsOn(checkFoundationPack)
}
