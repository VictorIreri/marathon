package com.malinskiy.marathon.android.executor.listeners.video

import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.server.junit5.AdbClient
import com.malinskiy.adam.server.junit5.AdbServer
import com.malinskiy.adam.server.junit5.AdbTest
import com.malinskiy.adam.server.stub.AndroidDebugBridgeServer
import com.malinskiy.marathon.android.adam.TestConfigurationFactory
import com.malinskiy.marathon.android.adam.TestDeviceFactory
import com.malinskiy.marathon.android.adam.boot
import com.malinskiy.marathon.android.adam.features
import com.malinskiy.marathon.android.adam.pullFile
import com.malinskiy.marathon.android.adam.shell
import com.malinskiy.marathon.android.model.TestIdentifier
import com.malinskiy.marathon.config.ScreenRecordingPolicy
import com.malinskiy.marathon.config.vendor.android.VideoConfiguration
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.device.toDeviceInfo
import com.malinskiy.marathon.io.FileManager
import com.malinskiy.marathon.io.FileType
import com.malinskiy.marathon.report.attachment.AttachmentListener
import com.malinskiy.marathon.test.TestBatch
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@AdbTest
class ScreenRecorderTestRunListenerTest {
    @AdbClient
    lateinit var client: AndroidDebugBridgeClient

    @AdbServer
    lateinit var server: AndroidDebugBridgeServer

    @TempDir
    lateinit var temp: File

    @Test
    fun testDefault() {
        val configuration = TestConfigurationFactory.create()
        val device = TestDeviceFactory.create(client, configuration, mock())
        val fileManager = mock<FileManager>()
        val devicePoolId = DevicePoolId("testpool")
        val batch = TestBatch(listOf(test1.toTest(), test2.toTest(), test3.toTest(), test4.toTest()))
        val attachmentListener = mock<AttachmentListener>()
        val videoFile = File(temp, "video")
        runBlocking {
            val listener = ScreenRecorderTestBatchListener(
                fileManager,
                devicePoolId,
                batch.id,
                device,
                VideoConfiguration(),
                ScreenRecordingPolicy.ON_FAILURE,
                this
            )
            listener.registerListener(attachmentListener)

            server.multipleSessions {
                serial("emulator-5554") {
                    boot()
                    shell(
                        "screenrecord --size 720x1280 --bit-rate 1000000 --time-limit 180 /sdcard/com.example.Class-method1-${batch.id}-0.mp4",
                        "",
                        delay = 1_000,
                    )
                    session {
                        delay(200)
                        respondOkay()
                        expectShell { "ps -A | grep screenrecord;echo x$?" }
                            .accept()
                            .respond("x0")
                    }

                    pullFile(temp, "/sdcard/com.example.Class-method1-${batch.id}-0.mp4")
                    shell("rm /sdcard/com.example.Class-method1-${batch.id}-0.mp4", "")
                }
                features("emulator-5554")
            }

            device.setup()
            whenever(fileManager.createFile(FileType.VIDEO, devicePoolId, device.toDeviceInfo(), test1.toTest(), batch.id, chunk = "0"))
                .thenReturn(videoFile)

            listener.testRunStarted("Testing", 1)
            listener.testStarted(test1)
            delay(100)
            listener.testFailed(test1, "trace")
            listener.testEnded(test1, mapOf())
        }
    }


    @Test
    fun testChunked() {
        val configuration = TestConfigurationFactory.create()
        val device = TestDeviceFactory.create(client, configuration, mock())
        val fileManager = mock<FileManager>()
        val devicePoolId = DevicePoolId("testpool")
        val batch = TestBatch(listOf(test1.toTest()))
        val attachmentListener = mock<AttachmentListener>()
        val videoFile1 = File(temp, "video1")
        val videoFile2 = File(temp, "video2")
        runBlocking {
            val listener = ScreenRecorderTestBatchListener(
                fileManager,
                devicePoolId,
                batch.id,
                device,
                VideoConfiguration(),
                ScreenRecordingPolicy.ON_FAILURE,
                this
            )
            listener.registerListener(attachmentListener)

            server.multipleSessions {
                serial("emulator-5554") {
                    boot()
                    shell(
                        "screenrecord --size 720x1280 --bit-rate 1000000 --time-limit 180 /sdcard/com.example.Class-method1-${batch.id}-0.mp4",
                        "",
                        delay = 1_000,
                    )
                    shell(
                        "screenrecord --size 720x1280 --bit-rate 1000000 --time-limit 180 /sdcard/com.example.Class-method1-${batch.id}-1.mp4",
                        "",
                        delay = 600,
                    )
                    session {
                        delay(200)
                        respondOkay()
                        expectShell { "ps -A | grep screenrecord;echo x$?" }
                            .accept()
                            .respond("x0")
                    }

                    pullFile(temp, "/sdcard/com.example.Class-method1-${batch.id}-0.mp4")
                    pullFile(temp, "/sdcard/com.example.Class-method1-${batch.id}-1.mp4")
                    shell("rm /sdcard/com.example.Class-method1-${batch.id}-0.mp4", "")
                    shell("rm /sdcard/com.example.Class-method1-${batch.id}-1.mp4", "")
                }
                features("emulator-5554")
            }

            device.setup()
            whenever(fileManager.createFile(FileType.VIDEO, devicePoolId, device.toDeviceInfo(), test1.toTest(), batch.id, chunk = "0"))
                .thenReturn(videoFile1)
            whenever(fileManager.createFile(FileType.VIDEO, devicePoolId, device.toDeviceInfo(), test1.toTest(), batch.id, chunk = "1"))
                .thenReturn(videoFile2)

            listener.testRunStarted("Testing", 1)
            listener.testStarted(test1)
            delay(1500)
            listener.testFailed(test1, "trace")
            listener.testEnded(test1, mapOf())
        }
    }

    @Test
    fun testAssumptionFailed() {
        val configuration = TestConfigurationFactory.create()
        val device = TestDeviceFactory.create(client, configuration, mock())
        val fileManager = mock<FileManager>()
        val devicePoolId = DevicePoolId("testpool")
        val batch = TestBatch(listOf(test1.toTest(), test2.toTest(), test3.toTest(), test4.toTest()))
        val attachmentListener = mock<AttachmentListener>()
        val videoFile = File(temp, "video")
        runBlocking {
            val listener = ScreenRecorderTestBatchListener(
                fileManager,
                devicePoolId,
                batch.id,
                device,
                VideoConfiguration(),
                ScreenRecordingPolicy.ON_FAILURE,
                this
            )
            listener.registerListener(attachmentListener)

            server.multipleSessions {
                serial("emulator-5554") {
                    boot()
                    shell(
                        "screenrecord --size 720x1280 --bit-rate 1000000 --time-limit 180 /sdcard/com.example.Class-method1-${batch.id}-0.mp4",
                        "",
                        delay = 1_000
                    )
                    session {
                        delay(200)
                        respondOkay()
                        expectShell { "ps -A | grep screenrecord;echo x$?" }
                            .accept()
                            .respond("x0")
                    }

                    pullFile(temp, "/sdcard/com.example.Class-method1-${batch.id}-0.mp4")
                    shell("rm /sdcard/com.example.Class-method1-${batch.id}-0.mp4", "")
                }
                features("emulator-5554")
            }

            device.setup()
            whenever(fileManager.createFile(FileType.VIDEO, devicePoolId, device.toDeviceInfo(), test1.toTest(), batch.id, chunk = "0"))
                .thenReturn(videoFile)

            listener.testRunStarted("Testing", 1)
            listener.testStarted(test1)
            delay(100)
            listener.testAssumptionFailure(test1, "trace")
            listener.testEnded(test1, mapOf())
        }
    }

    @Test
    fun testRunFailed() {
        val configuration = TestConfigurationFactory.create()
        val device = TestDeviceFactory.create(client, configuration, mock())
        val fileManager = mock<FileManager>()
        val devicePoolId = DevicePoolId("testpool")
        val batch = TestBatch(listOf(test1.toTest(), test2.toTest(), test3.toTest(), test4.toTest()))
        val attachmentListener = mock<AttachmentListener>()
        val videoFile = File(temp, "video")
        runBlocking {
            val listener = ScreenRecorderTestBatchListener(
                fileManager,
                devicePoolId,
                batch.id,
                device,
                VideoConfiguration(),
                ScreenRecordingPolicy.ON_FAILURE,
                this
            )
            listener.registerListener(attachmentListener)

            server.multipleSessions {
                serial("emulator-5554") {
                    boot()
                    shell(
                        "screenrecord --size 720x1280 --bit-rate 1000000 --time-limit 180 /sdcard/com.example.Class-method1-${batch.id}-0.mp4",
                        "",
                        delay = 1_000
                    )
                    session {
                        delay(200)
                        respondOkay()
                        expectShell { "echo quickhealthcheck;echo x$?" }
                            .accept()
                            .respond("quickhealthcheck\nx0")
                    }
                    shell("ps -A | grep screenrecord", "")

                    pullFile(temp, "/sdcard/com.example.Class-method1-${batch.id}-0.mp4")
                    shell("rm /sdcard/com.example.Class-method1-${batch.id}-0.mp4", "")
                }
                features("emulator-5554")
            }

            device.setup()
            whenever(fileManager.createFile(FileType.VIDEO, devicePoolId, device.toDeviceInfo(), testBatchId = batch.id, chunk = "0"))
                .thenReturn(videoFile)

            listener.testRunStarted("Testing", 1)
            listener.testStarted(test1)
            delay(100)
            listener.testRunFailed("trace")
        }
    }

    companion object {
        val test1 = TestIdentifier("com.example.Class", "method1")
        val test2 = TestIdentifier("com.example.Class", "method2")
        val test3 = TestIdentifier("com.example.Class", "method3")
        val test4 = TestIdentifier("com.example.Class", "method4")
    }
}
