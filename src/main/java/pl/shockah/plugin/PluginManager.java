package pl.shockah.plugin;

import org.apache.commons.io.IOUtils;
import pl.shockah.json.JSONObject;
import pl.shockah.json.JSONParser;
import pl.shockah.util.FileUtils;
import pl.shockah.util.ReadWriteList;
import pl.shockah.util.ReadWriteMap;
import pl.shockah.util.UnexpectedException;
import pl.shockah.util.func.Func2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PluginManager<I extends PluginInfo, M extends PluginManager<I, M, P>, P extends Plugin<I, M, P>> {
	@Nonnull public static final Path PLUGINS_PATH = Paths.get("plugins");

	@Nonnull protected final Class<I> pluginInfoClass;
	@Nonnull protected final Class<P> pluginClass;
	@Nonnull protected final Path pluginsPath;
	
	public ClassLoader pluginClassLoader = null;
	@Nonnull public final ReadWriteList<I> pluginInfos = new ReadWriteList<>(new ArrayList<>());
	@Nonnull public final ReadWriteList<P> plugins = new ReadWriteList<>(new ArrayList<>());

	@Nonnull protected final ReadWriteMap<String, Func2<ClassLoader, URL[], URLClassLoader>> customClassLoaderProviders = new ReadWriteMap<>(new HashMap<>());
	@Nonnull protected final ReadWriteMap<String, URLClassLoader> customClassLoaders = new ReadWriteMap<>(new HashMap<>());
	
	public PluginManager(@Nonnull Class<I> pluginInfoClass, @Nonnull Class<P> pluginClass) {
		this(pluginInfoClass, pluginClass, PLUGINS_PATH);
	}
	
	public PluginManager(@Nonnull Class<I> pluginInfoClass, @Nonnull Class<P> pluginClass, @Nonnull Path pluginsPath) {
		this.pluginInfoClass = pluginInfoClass;
		this.pluginClass = pluginClass;
		this.pluginsPath = pluginsPath;
	}

	@Nonnull public Path getPluginPath() {
		return pluginsPath;
	}
	
	public void reloadAll() {
		unloadAll();
		loadAll();
	}
	
	protected void unloadAll() {
		plugins.writeOperation(plugins -> {
			List<P> reversed = new ArrayList<>(plugins);
			Collections.reverse(reversed);
			for (P plugin : reversed) {
				unloadInternal(plugin);
			}

			customClassLoaders.clear();
			customClassLoaderProviders.clear();
			plugins.clear();
			pluginInfos.clear();
			pluginClassLoader = null;
		});
	}
	
	protected void loadAll() {
		plugins.writeOperation(plugins -> {
			List<I> infos = findPlugins();
			pluginInfos.addAll(infos);
			pluginClassLoader = createClassLoader(pluginInfos);

			for (I pluginInfo : infos) {
				load(pluginInfo);
			}

			plugins.forEach(this::setupOptionalDependencyFields);
			plugins.forEach(Plugin::onAllPluginsLoaded);
		});
	}

	public void load(@Nonnull I pluginInfo) {
		load(pluginInfo, true);
	}

	public void load(@Nonnull I pluginInfo, boolean withDependencies) {
		plugins.writeOperation(plugins -> {
			if (getPlugin(pluginInfo) != null)
				return;

			for (String dependency : pluginInfo.getDependencies()) {
				if (getPlugin(dependency) == null) {
					if (withDependencies) {
						I dependencyInfo = getPluginInfo(dependency);
						if (dependencyInfo != null) {
							load(dependencyInfo, true);
							continue;
						}
					}

					throw new IllegalStateException(String.format(
							"Plugin `%s` cannot be loaded without loading plugin `%s`.",
							pluginInfo.getPackageName(),
							dependency
					));
				}
			}
			loadInternal(pluginInfo);
		});
	}

	@Nullable private P loadInternal(@Nonnull I pluginInfo) {
		if (shouldEnable(pluginInfo)) {
			P plugin = loadPlugin(pluginClassLoader, pluginInfo);
			if (plugin != null) {
				try {
					setupClassLoaderProviders(plugin);
					plugins.add(plugin);
					onPluginLoad(plugin);
				} catch (Exception e) {
					throw new UnexpectedException(e);
				}
			}
			return plugin;
		}
		return null;
	}

	public void unload(@Nonnull P plugin) {
		unload(plugin, true);
	}

	public void unload(@Nonnull P plugin, boolean withDependants) {
		plugins.writeOperation(plugins -> {
			for (P anyPlugin : plugins) {
				if (anyPlugin == plugin)
					continue;
				if (anyPlugin.loadedDependencies.contains(plugin)) {
					if (withDependants)
						unload(anyPlugin, true);
					else
						throw new IllegalStateException(String.format(
								"Plugin `%s` cannot be unloaded without unloading plugin `%s`.",
								plugin.info.getPackageName(),
								anyPlugin.info.getPackageName()
						));
				}
			}
			unloadInternal(plugin);
		});
	}

	private void unloadInternal(@Nonnull P plugin) {
		plugin.onUnload();
		onPluginUnload(plugin);
	}
	
	protected void onPluginLoad(@Nonnull P plugin) {
		System.out.println(String.format("Loaded plugin: %s", plugin.info.getPackageName()));
	}
	
	protected void onPluginUnload(@Nonnull P plugin) {
		System.out.println(String.format("Unloaded plugin: %s", plugin.info.getPackageName()));
	}
	
	@SuppressWarnings("unchecked")
	@Nullable protected P loadPlugin(@Nonnull ClassLoader classLoader, @Nonnull I info) {
		try {
			String infoClassLoader = info.getClassLoader();
			if (!infoClassLoader.equals("default"))
				customClassLoaders.computeIfAbsent(infoClassLoader, key -> customClassLoaderProviders.get(key).call(classLoader, createURLArray(pluginInfos)));
			
			Class<?> clazz = classLoader.loadClass(info.getBaseClass());
			L: for (Constructor<?> ctor : clazz.getConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				if (params.length >= 2 && getClass().isAssignableFrom(params[0]) && params[1] == pluginInfoClass) {
					AnnotatedType[] annotatedTypes = ctor.getAnnotatedParameterTypes();
					Object[] ctorArgs = new Object[params.length];
					ctorArgs[0] = this;
					ctorArgs[1] = info;
					for (int i = 2; i < params.length; i++) {
						Plugin.RequiredDependency dependencyAnnotation = annotatedTypes[i].getAnnotation(Plugin.RequiredDependency.class);
						if (dependencyAnnotation == null)
							continue L;
						Object dependency = getPluginWithClass(params[i]);
						if (dependency == null)
							continue L;
						ctorArgs[i] = dependency;
					}
					return (P)ctor.newInstance(ctorArgs);
				}
			}
			throw new NoSuchMethodException("Missing plugin constructor.");
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	protected void setupClassLoaderProviders(@Nonnull P plugin) {
		for (Method method : plugin.getClass().getDeclaredMethods()) {
			try {
				Plugin.ClassLoaderProvider classLoaderProviderAnnotation = method.getAnnotation(Plugin.ClassLoaderProvider.class);
				if (classLoaderProviderAnnotation != null) {
					customClassLoaderProviders.put(classLoaderProviderAnnotation.value(), (cl, urls) -> {
						try {
							return (URLClassLoader)method.invoke(plugin, cl, urls);
						} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
							throw new UnexpectedException(e);
						}
					});
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	protected void setupOptionalDependencyFields(@Nonnull P plugin) {
		for (Field field : plugin.getClass().getDeclaredFields()) {
			try {
				Plugin.OptionalDependency dependencyAnnotation = field.getAnnotation(Plugin.OptionalDependency.class);
				if (dependencyAnnotation == null)
					continue;

				P dependency = getPlugin(dependencyAnnotation.value());
				if (dependency != null) {
					field.setAccessible(true);
					field.set(plugin, dependency);
					plugin.onDependencyLoaded(plugin);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	@Nullable public <P2> P2 getPluginWithClass(@Nonnull Class<P2> clazz) {
		return (P2)plugins.filterFirst(clazz::isInstance);
	}
	
	@SuppressWarnings("unchecked")
	@Nonnull public <P2> List<P2> getPluginsWithClass(@Nonnull Class<P2> clazz) {
		List<P2> ret = new ArrayList<>();
		plugins.iterate(plugin -> {
			if (clazz.isInstance(plugin))
				ret.add((P2)plugin);
		});
		return ret;
	}

	@Nullable public I getPluginInfo(@Nonnull String packageName) {
		return pluginInfos.filterFirst(pluginInfo -> pluginInfo.getPackageName().equals(packageName));
	}
	
	@SuppressWarnings("unchecked")
	@Nullable public <P2 extends P> P2 getPlugin(@Nonnull String packageName) {
		return (P2)plugins.filterFirst(plugin -> plugin.info.getPackageName().equals(packageName));
	}

	@Nullable public <P2 extends P> P2 getPlugin(@Nonnull PluginInfo pluginInfo) {
		return getPlugin(pluginInfo.getPackageName());
	}

	@SuppressWarnings("unchecked")
	@Nonnull protected List<I> findPlugins() {
		List<I> infos = new ArrayList<>();
		
		try {
			for (Path path : Files.newDirectoryStream(getPluginPath(), path -> path.getFileName().toString().endsWith(".jar"))) {
				Path tmpPath = FileUtils.copyAsTrueTempFile(path);
				
				try (ZipFile zf = new ZipFile(tmpPath.toFile())) {
					ZipEntry ze = zf.getEntry("plugin.json");
					if (ze == null)
						continue;
					
					JSONObject pluginJson = new JSONParser().parseObject(new String(IOUtils.toByteArray(zf.getInputStream(ze)), "UTF-8"));
					I pluginInfo = (I)pluginInfoClass.getConstructors()[0].newInstance(pluginJson, tmpPath.toUri().toURL());
					infos.add(pluginInfo);
				} catch (Exception e) {
					throw new UnexpectedException(e);
				}
			}
		} catch (Exception e) {
			throw new UnexpectedException(e);
		}
		
		return infos;
	}

	@Nonnull protected URL[] createURLArray(@Nonnull ReadWriteList<I> infos) {
		List<URL> urls = new ArrayList<>();
		infos.iterate(info -> urls.add(info.url));
		return urls.toArray(new URL[0]);
	}

	@Nonnull protected ClassLoader createClassLoader(@Nonnull ReadWriteList<I> infos) {
		return new URLClassLoader(createURLArray(infos));
	}
	
	protected boolean shouldEnable(@Nonnull I info) {
		return info.isEnabledByDefault();
	}
}