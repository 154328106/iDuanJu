package app.iduanju

import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor

/** Export via shell stdin: scoped app storage may disappear when Gradle uninstalls the test app. */
internal fun saveNovelEvidence(name:String, bytes:ByteArray) {
    require(name.matches(Regex("[a-z0-9.-]+")))
    val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
    val pipes=automation.executeShellCommandRw("sh -c 'mkdir -p /sdcard/Download/novel-qa; cat > /sdcard/Download/novel-qa/$name'")
    ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{it.write(bytes)}
    ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use{it.readBytes()}
}
