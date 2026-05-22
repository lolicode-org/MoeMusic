package org.lolicode.moemusic.core.plugin

import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginProvider
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.extension

internal object PluginJarDiscovery {

    private const val SERVICE_FILE = "META-INF/services/org.lolicode.moemusic.api.plugin.PluginProvider"

    private val logger = LoggerFactory.getLogger(PluginJarDiscovery::class.java)

    fun discover(pluginDir: Path): LoadedPluginJars {
        pluginDir.createDirectories()

        val jars = Files.list(pluginDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension.equals("jar", ignoreCase = true) }
                .sorted(compareBy<Path> { it.fileName.toString().lowercase() }.thenBy { it.fileName.toString() })
                .toList()
        }
        if (jars.isEmpty()) return LoadedPluginJars(emptyList(), emptyList())

        val plugins = mutableListOf<DiscoveredPlugin>()
        val classLoaders = mutableListOf<Closeable>()
        try {
            for (jar in jars) {
                val providerNames = try {
                    readProviderClassNames(jar)
                } catch (e: Exception) {
                    throw pluginLoadError(jar, "Could not read provider service descriptor.", e)
                }
                if (providerNames.isEmpty()) {
                    logger.warn(
                        "Skipping MoeMusic plugin jar '{}' because it has no {} service descriptor.",
                        jar.fileName,
                        SERVICE_FILE,
                    )
                    continue
                }

                val classLoader = StandalonePluginClassLoader(
                    jar,
                    PluginJarDiscovery::class.java.classLoader,
                )
                val jarPlugins = try {
                    loadProviders(jar, classLoader, providerNames)
                } catch (e: Exception) {
                    closeQuietly(classLoader)
                    throw e
                }
                if (jarPlugins.isEmpty()) {
                    classLoader.close()
                } else {
                    classLoaders += classLoader
                    plugins += jarPlugins
                }
            }
        } catch (e: Exception) {
            classLoaders.forEach { closeQuietly(it) }
            throw e
        }

        return LoadedPluginJars(plugins, classLoaders)
    }

    private fun readProviderClassNames(jar: Path): List<String> =
        JarFile(jar.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry(SERVICE_FILE) ?: return emptyList()
            jarFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map { line -> line.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toList()
            }
        }

    private fun loadProviders(
        jar: Path,
        classLoader: ClassLoader,
        providerNames: List<String>,
    ): List<DiscoveredPlugin> {
        val plugins = mutableListOf<DiscoveredPlugin>()
        for (providerName in providerNames) {
            val provider = instantiateProvider(jar, classLoader, providerName)
            val providedPlugins: Iterable<Plugin>? = try {
                provider.plugins()
            } catch (e: Exception) {
                throw pluginLoadError(jar, "Provider '$providerName' threw while listing plugins.", e)
            } catch (e: LinkageError) {
                throw pluginLoadError(jar, "Provider '$providerName' threw while listing plugins.", e)
            }
            if (providedPlugins == null) {
                throw pluginLoadError(jar, "Provider '$providerName' returned a null plugin list.", null)
            }
            var index = 0
            @Suppress("UNCHECKED_CAST")
            for (plugin in providedPlugins as Iterable<Plugin?>) {
                if (plugin == null) {
                    throw pluginLoadError(jar, "Provider '$providerName' returned a null plugin at index $index.", null)
                }
                plugins += DiscoveredPlugin(
                    plugin = plugin,
                    origin = "standalone plugin jar '${jar.fileName}' provider '$providerName'",
                )
                index += 1
            }
        }
        return plugins
    }

    private fun instantiateProvider(jar: Path, classLoader: ClassLoader, providerName: String): PluginProvider {
        val providerClass = try {
            Class.forName(providerName, true, classLoader)
        } catch (e: ClassNotFoundException) {
            throw pluginLoadError(jar, "Provider class '$providerName' was not found.", e)
        } catch (e: LinkageError) {
            throw pluginLoadError(jar, "Provider class '$providerName' could not be linked.", e)
        }

        if (!PluginProvider::class.java.isAssignableFrom(providerClass)) {
            throw pluginLoadError(
                jar,
                "Provider class '$providerName' does not implement ${PluginProvider::class.java.name}.",
                null,
            )
        }

        val constructor = try {
            providerClass.asSubclass(PluginProvider::class.java).getConstructor()
        } catch (e: NoSuchMethodException) {
            throw pluginLoadError(jar, "Provider class '$providerName' has no public no-argument constructor.", e)
        }

        return try {
            constructor.newInstance()
        } catch (e: InvocationTargetException) {
            throw pluginLoadError(jar, "Provider class '$providerName' threw during construction.", e.targetException)
        } catch (e: ReflectiveOperationException) {
            throw pluginLoadError(jar, "Provider class '$providerName' could not be constructed.", e)
        } catch (e: LinkageError) {
            throw pluginLoadError(jar, "Provider class '$providerName' could not be linked during construction.", e)
        }
    }

    private fun pluginLoadError(jar: Path, message: String, cause: Throwable?): IllegalStateException =
        IllegalStateException("Failed to load MoeMusic plugin jar '${jar.fileName}': $message", cause)

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // Keep the original plugin load failure visible.
        }
    }

    data class LoadedPluginJars(
        val plugins: List<DiscoveredPlugin>,
        val classLoaders: List<Closeable>,
    ) {
        fun close() {
            classLoaders.forEach { PluginJarDiscovery.closeQuietly(it) }
        }
    }

    data class DiscoveredPlugin(
        val plugin: Plugin,
        val origin: String,
    )

    private class StandalonePluginClassLoader(
        pluginJar: Path,
        parent: ClassLoader,
    ) : URLClassLoader(arrayOf(pluginJar.toUri().toURL()), parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                val alreadyLoaded = findLoadedClass(name)
                if (alreadyLoaded != null) {
                    if (resolve) resolveClass(alreadyLoaded)
                    return alreadyLoaded
                }

                val loaded = if (isParentFirst(name)) {
                    super.loadClass(name, false)
                } else {
                    try {
                        findClass(name)
                    } catch (_: ClassNotFoundException) {
                        super.loadClass(name, false)
                    }
                }

                if (resolve) resolveClass(loaded)
                loaded
            }

        private fun isParentFirst(name: String): Boolean =
            name.startsWith("java.") ||
                    name.startsWith("javax.") ||
                    name.startsWith("jdk.") ||
                    name.startsWith("sun.") ||
                    name.startsWith("kotlin.") ||
                    name.startsWith("kotlinx.") ||
                    name.startsWith("org.slf4j.") ||
                    name.startsWith("org.lolicode.moemusic.api.")
    }
}
