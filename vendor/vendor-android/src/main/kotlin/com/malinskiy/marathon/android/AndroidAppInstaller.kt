package com.malinskiy.marathon.android

import com.malinskiy.marathon.android.exception.DeviceNotSupportedException
import com.malinskiy.marathon.android.exception.InstallException
import com.malinskiy.marathon.android.extension.testBundlesCompat
import com.malinskiy.marathon.android.model.AndroidTestBundle
import com.malinskiy.marathon.config.Configuration
import com.malinskiy.marathon.config.vendor.VendorConfiguration
import com.malinskiy.marathon.exceptions.DeviceSetupException
import com.malinskiy.marathon.execution.withRetry
import com.malinskiy.marathon.log.MarathonLogging
import java.io.File

class AndroidAppInstaller(configuration: Configuration) {

    companion object {
        private const val MAX_RETIRES = 3
        private const val MARSHMALLOW_VERSION_CODE = 23
    }

    private val logger = MarathonLogging.logger("AndroidAppInstaller")
    private val androidConfiguration = configuration.vendorConfiguration as VendorConfiguration.AndroidConfiguration

    /**
     * @throws DeviceSetupException if unable to prepare the device
     */
    suspend fun prepareInstallation(device: AndroidDevice) {
        val testBundles = androidConfiguration.testBundlesCompat()
        testBundles.forEach { bundle ->
            preparePartialInstallation(device, bundle)
        }
    }

    suspend fun preparePartialInstallation(device: AndroidDevice, bundle: AndroidTestBundle) {
        val applicationInfo = bundle.instrumentationInfo
        logger.debug { "Installing application output to ${device.serialNumber}" }
        bundle.application?.let { applicationApk ->
            if (bundle.splitApks.isNullOrEmpty()) {
                if (device.apiLevel < 21) {
                    throw DeviceNotSupportedException("Device api level should be more then 20")
                }
            }
            reinstall(device, applicationInfo.applicationPackage, applicationApk, bundle.splitApks ?: emptyList())
            appops(device, applicationInfo.applicationPackage)
        }

        bundle.extraApplications?.let { extraApplications ->
            extraApplications.forEach { extraApplication ->
                logger.debug { "Installing extra application to ${device.serialNumber}" }
                val extraApplicationPackage = AndroidTestBundle.apkParser.parseAppPackageName(extraApplication)
                reinstall(device, extraApplicationPackage, extraApplication)
            }
        }

        logger.debug { "Installing instrumentation package to ${device.serialNumber}" }
        reinstall(device, applicationInfo.instrumentationPackage, bundle.testApplication)
        logger.debug { "Prepare installation finished for ${device.serialNumber}" }
    }

    private suspend fun appops(device: AndroidDevice, applicationPackage: String) {
        if (androidConfiguration.mockLocation) {
            if (device.apiLevel < 23) {
                logger.warn { "Can't setup mock location: device ${device.serialNumber} doesn't support appops" }
                return
            }

            val appopsMessage = device.criticalExecuteShellCommand("appops set $applicationPackage android:mock_location allow")
            appopsMessage.let {
                if (it.exitCode != 0) {
                    val (output, _) = device.criticalExecuteShellCommand("appops query-op android:mock_location allow")
                    logger.error { "Can't set android:mock_location on $applicationPackage. List of apps currently using android:mock_location:$output" }
                }
            }
        }
    }

    /**
     * @throws DeviceSetupException if unable to reinstall (even with retries)
     */
    private suspend fun reinstall(device: AndroidDevice, appPackage: String, applicationApk: File, splitApks: List<File> = emptyList()) {
        val absolutePaths = if (splitApks.isNotEmpty()) {
            listOf(applicationApk, *splitApks.toTypedArray()).map { splitApk -> splitApk.absolutePath }
        } else {
            listOf<String>(applicationApk.absolutePath)
        }

        withRetry(attempts = MAX_RETIRES, delayTime = 1000) {
            try {
                if (installed(device, appPackage)) {
                    logger.info("Uninstalling $appPackage from ${device.serialNumber}")
                    val uninstallMessage = device.safeUninstallPackage(appPackage)?.output?.trim()
                    uninstallMessage?.let { logger.debug { it } }
                }
                logger.info("Installing $appPackage, ${absolutePaths.joinToString()} to ${device.serialNumber}")

                val installMessage = when {
                    splitApks.isEmpty() -> device.installPackage(applicationApk.absolutePath, true, optionalParams(device))?.output?.trim()
                    else -> device.installSplitPackages(absolutePaths, true, optionalParams(device))
                }
                installMessage?.let { logger.debug { it } }
                if (installMessage == null || !installMessage.contains("Success")) {
                    throw InstallException(installMessage ?: "")
                }
            } catch (e: InstallException) {
                logger.error(e) { "Error while installing $appPackage, ${absolutePaths.joinToString()} on ${device.serialNumber}" }
                throw DeviceSetupException("Error while installing $appPackage on ${device.serialNumber}", e)
            }
        }
    }

    /**
     * @throws DeviceSetupException if unable to verify
     */
    private suspend fun installed(device: AndroidDevice, appPackage: String): Boolean {
        val lines = device.safeExecuteShellCommand("pm list packages")?.output?.lines()
            ?: throw DeviceSetupException("Unable to verify that package $appPackage is installed")
        return lines.any { it == "package:$appPackage" }
    }

    private fun optionalParams(device: AndroidDevice): List<String> {
        return mutableListOf<String>().apply {
            if (device.apiLevel >= MARSHMALLOW_VERSION_CODE && androidConfiguration.autoGrantPermission) {
                add("-g")
            }
            if (androidConfiguration.installOptions.isNotEmpty()) {
                addAll(androidConfiguration.installOptions.split(" "))
            }
        }.toList()
    }
}
