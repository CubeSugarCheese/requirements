package ru.meanmail

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.packaging.*
import ru.meanmail.psi.RequirementsPsiImplUtil
import java.io.File
import java.time.LocalDateTime


fun getPackage(project: Project, packageName: String): PyPackage? {
    val packageManager = RequirementsPsiImplUtil
            .getPackageManager(project) ?: return null
    val packages = packageManager.refreshAndGetPackages(false)
    return PyPackageUtil.findPackage(packages, packageName)
}

fun getCurrentVersion(project: Project, packageName: String): PyPackageVersion? {
    val pyPackage = getPackage(project, packageName) ?: return null
    if (!pyPackage.isInstalled) {
        return null
    }
    return PyPackageVersionNormalizer
            .normalize(pyPackage.version) ?: return null
}

fun getVersions(project: Project,
                packageName: String,
                version: String?): Triple<PyPackageVersion?, PyPackageVersion?, PyPackageVersion?> {
    val installed = getCurrentVersion(project, packageName)
    val latest = getLatestVersion(project, packageName)
    val required = if (version == null) {
        latest
    } else {
        PyPackageVersionNormalizer.normalize(version)
    }
    
    return Triple(
            first = required,
            second = installed,
            third = latest
    )
}

val cache = mutableMapOf<String, Pair<PyPackageVersion?, LocalDateTime>>()

fun getLatestVersion(project: Project, packageName: String): PyPackageVersion? {
    val key = packageName.toLowerCase()
    val cached = cache.get(key)
    if (cached != null) {
        val actual = cached.second.plusDays(1).isAfter(LocalDateTime.now())
        if (actual) {
            return cached.first
        }
    }
    val latestVersion = PyPIPackageUtil.INSTANCE
            .fetchLatestPackageVersion(project, packageName) ?: return null
    val version = PyPackageVersionNormalizer
            .normalize(latestVersion) ?: return null
    cache[key] = version to LocalDateTime.now()
    return version
}

fun installPackage(project: Project, packageName: String,
                   version: String, relation: String,
                   onInstalled: (() -> Unit)?) {
    val text = "$packageName$relation$version"
    val title = "Installing '$text'"
    
    // TODO Use relation
    
    val task = object : Task.Backgroundable(project, title) {
        override fun run(indicator: ProgressIndicator) {
            indicator.text = this.title
            indicator.isIndeterminate = true
            
            val application = ApplicationManager.getApplication()
            
            application.runReadAction {
                try {
                    val packageManager = RequirementsPsiImplUtil.getPackageManager(project)
                    
                    if (packageManager != null) {
                        packageManager.install(text)
                    } else {
                        Notification("pip",
                                title,
                                "Package manager is not available",
                                NotificationType.ERROR).notify(project)
                        return@runReadAction
                    }
                    val pyPackage = getPackage(project, packageName)
                    
                    if (pyPackage == null) {
                        Notification("pip",
                                title,
                                "Failed. Not installed",
                                NotificationType.ERROR).notify(project)
                        return@runReadAction
                    }
                    
                    Notification("pip",
                            "${pyPackage.name} (${pyPackage.version})",
                            "Successfully installed",
                            NotificationType.INFORMATION).notify(project)
                    if (onInstalled != null) {
                        onInstalled()
                    }
                } catch (e: PyExecutionException) {
                    Notification(e.command,
                            e.stdout,
                            e.stderr,
                            NotificationType.ERROR).notify(project)
                }
            }
        }
    }
    
    ProgressManager.getInstance().run(task)
}

fun resolveFile(filepath: String, base: VirtualFile): VirtualFile? {
    val target = File(filepath)
    return if (target.isAbsolute) {
        LocalFileSystem.getInstance().findFileByIoFile(target)
    } else {
        base.findFileByRelativePath(filepath)
    }
}

operator fun PyPackageVersion?.compareTo(b: PyPackageVersion?): Int {
    if (this == null && b == null) {
        return 0
    }
    if (this == null) {
        return -1
    }
    if (b == null) {
        return 1
    }
    return PyPackageVersionComparator.compare(this, b)
}