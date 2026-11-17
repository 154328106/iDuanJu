package app.iduanju

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileOutputStream

/** Export via shell stdin: scoped app storage may disappear when Gradle uninstalls the test app. */
internal fun saveNovelEvidence(name:String, bytes:ByteArray) {
    require(name.matches(Regex("[a-z0-9.-]+")))
    val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
    val pipes=automation.executeShellCommandRw("sh -c 'mkdir -p /sdcard/Download/novel-qa; cat > /sdcard/Download/novel-qa/$name'")
    FileOutputStream(pipes[1].fileDescriptor).use{it.write(bytes)}
    java.io.FileInputStream(pipes[0].fileDescriptor).use{it.readBytes()}
    pipes.forEach {runCatching{it.close()}}
}
